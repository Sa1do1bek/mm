package com.enterprise.mm.service;

import com.enterprise.mm.payload.MrpDtos.MrpRunResultDto;
import com.enterprise.mm.payload.MrpDtos.MrpShortageDto;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MrpService {
    private static final List<String> OPEN_PR_STATUSES = List.of(
        PurchaseRequestStatus.DRAFT.name(),
        PurchaseRequestStatus.APPROVAL_PENDING.name(),
        PurchaseRequestStatus.APPROVED.name(),
        PurchaseRequestStatus.RFQ_CREATED.name(),
        PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
        PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name()
    );
    private final MaterialRepository materialRepository;
    private final com.enterprise.mm.security.pilot.PilotCapabilityPolicy pilotCapabilityPolicy;
    private final StockBalanceRepository stockBalanceRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final NotificationService notificationService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final ProcurementDocumentCategoryValidator documentCategoryValidator;

    @Transactional(readOnly = true)
    public List<MrpShortageDto> calculateShortages(String search) {
        List<MrpShortageDto> shortages = calculateShortages();
        if (search == null || search.isBlank()) {
            return shortages;
        }
        String needle = search.trim().toLowerCase(java.util.Locale.ROOT);
        return shortages.stream()
            .filter(item -> contains(item.materialCode(), needle) || contains(item.materialName(), needle))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<MrpShortageDto> calculateShortages() {
        List<MrpShortageDto> shortages = new ArrayList<>();
        String tenantId = TenantContext.currentTenant();
        for (Material material : materialRepository.findByTenantIdAndStatusAndIsDeletedFalse(tenantId, MaterialStatus.ACTIVE.name())) {
            BigDecimal threshold = reorderThreshold(material);
            if (threshold.signum() <= 0) {
                continue;
            }
            BigDecimal available = nvl(stockBalanceRepository.sumAvailableQuantityByMaterial(tenantId, material.getId()));
            BigDecimal openPr = nvl(purchaseRequestItemRepository.sumOpenQuantityByMaterial(tenantId, material.getId(), OPEN_PR_STATUSES));
            BigDecimal shortage = threshold.subtract(available).subtract(openPr);
            if (shortage.signum() > 0) {
                BigDecimal recommended = shortage.max(nvl(material.getSafetyStock()));
                shortages.add(new MrpShortageDto(
                    material.getId(),
                    material.getCode(),
                    material.getName(),
                    available,
                    openPr,
                    nvl(material.getMinStock()),
                    nvl(material.getSafetyStock()),
                    nvl(material.getReorderPoint()),
                    shortage,
                    recommended,
                    BigDecimal.ZERO
                ));
            }
        }
        return shortages;
    }
    @Scheduled(cron = "0 10 3 * * *")
    @Transactional
    public void scheduledRun() {
        if (!pilotCapabilityPolicy.enabled(com.enterprise.mm.security.pilot.PilotCapability.MRP_GENERATION)) return;
        runAndCreatePurchaseRequestsForAllTenants();
    }
    @Transactional
    public List<MrpRunResultDto> runAndCreatePurchaseRequestsForAllTenants() {
        pilotCapabilityPolicy.require(com.enterprise.mm.security.pilot.PilotCapability.MRP_GENERATION);
        String previousTenant = TenantContext.currentTenant();
        try {
            return materialRepository.findDistinctTenantIdsByStatus(MaterialStatus.ACTIVE.name()).stream()
                .map(tenantId -> {
                    TenantContext.setTenant(tenantId);
                    return runAndCreatePurchaseRequests();
                })
                .toList();
        } finally {
            TenantContext.setTenant(previousTenant);
        }
    }
    @Transactional
    public MrpRunResultDto runAndCreatePurchaseRequests() {
        pilotCapabilityPolicy.require(com.enterprise.mm.security.pilot.PilotCapability.MRP_GENERATION);
        List<MrpShortageDto> shortages = calculateShortages();
        if (shortages.isEmpty()) {
            auditService.log("MRP", null, "RUN_NO_SHORTAGES", "No material shortage detected", null, null);
            return new MrpRunResultDto(0, 0, BigDecimal.ZERO, shortages, List.of());
        }
        Map<UUID, MaterialType> materialTypes = shortages.stream()
            .map(MrpShortageDto::materialId)
            .distinct()
            .collect(java.util.stream.Collectors.toMap(id -> id, this::materialType));
        documentCategoryValidator.validateNoMixedGoodsBusinessTypes("purchaseRequest", materialTypes.values());
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("MRP-PR-" + UUID.randomUUID().toString().substring(0, 8));
        request.setName(BusinessNameGenerator.workflowDocumentName("PurchaseRequest", "MRP replenishment"));
        request.setStatus(PurchaseRequestStatus.DRAFT.name());
        request.setRequestType(RequestType.GOODS);
        request.setRequestedBy("MRP");
        request.setRequiredDate(LocalDate.now().plusDays(14));
        request.setCurrencyCode("UZS");
        request.setTotalAmount(shortages.stream().map(MrpShortageDto::estimatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        PurchaseRequest savedRequest = purchaseRequestRepository.save(request);
        for (MrpShortageDto shortage : shortages) {
            PurchaseRequestItem item = new PurchaseRequestItem();
            item.setCode("MRP-ITEM-" + UUID.randomUUID().toString().substring(0, 8));
            item.setName(shortage.materialName());
            item.setStatus("OPEN");
            item.setPurchaseRequestId(savedRequest.getId());
            item.setMaterialId(shortage.materialId());
            item.setMaterialTypeSnapshot(materialTypes.getOrDefault(shortage.materialId(), MaterialType.TMB));
            item.setQuantity(shortage.recommendedPurchaseQuantity());
            item.setEstimatedPrice(shortage.estimatedAmount());
            purchaseRequestItemRepository.save(item);
            notifyShortage(shortage);
        }
        integrationEventService.emit("MM_MRP_PR_CREATED", "PurchaseRequest", savedRequest.getId(), Map.of("source", "MRP", "purchaseRequestId", savedRequest.getId()));
        auditService.log("MRP", savedRequest.getId(), "CREATE_PR", shortages.size() + " shortages", null, null);
        return new MrpRunResultDto(shortages.size(), 1, savedRequest.getTotalAmount(), shortages, List.of(savedRequest.getId()));
    }
    private void notifyShortage(MrpShortageDto shortage) {
        notificationService.createForUsernameOrRole(
            "MM_MANAGER",
            "notification.mrp.shortage.title",
            "notification.mrp.shortage.message",
            Map.of(
                "materialCode", shortage.materialCode() == null ? "" : shortage.materialCode(),
                "quantity", shortage.recommendedPurchaseQuantity()
            ),
            null
        );
    }
    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    private BigDecimal reorderThreshold(Material material) {
        BigDecimal reorderPoint = nvl(material.getReorderPoint());
        if (reorderPoint.signum() > 0) {
            return reorderPoint;
        }
        return nvl(material.getMinStock()).add(nvl(material.getSafetyStock()));
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private MaterialType materialType(UUID materialId) {
        return materialRepository.findByIdAndIsDeletedFalse(materialId)
            .map(Material::getMaterialType)
            .orElse(MaterialType.TMB);
    }
}

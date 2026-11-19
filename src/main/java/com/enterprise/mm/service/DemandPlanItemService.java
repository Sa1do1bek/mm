package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.DemandPlanItemResponse;
import com.enterprise.mm.payload.DemandPlanItemUpdateRequest;
import com.enterprise.mm.payload.DemandPlanItemCreateRequest;
import com.enterprise.mm.payload.DemandPlanItemMonthDto;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.DemandPlanItemMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.DemandPlanItemStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.DemandPriority;
import com.enterprise.mm.enums.DemandType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.DemandPlanItemMonth;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.repository.DemandPlanItemMonthRepository;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.DemandPlanItemSearchRequest;

@Service
public class DemandPlanItemService {
    private final DemandPlanItemRepository repository;
    private final DemandPlanItemMonthRepository monthRepository;
    private final DemandPlanItemMapper mapper;
    private final String entityName = "DemandPlanItem";
    private final DemandPlanRepository demandPlanRepository;
    private final DemandPlanningCycleRepository demandPlanningCycleRepository;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    private final MaterialBusinessTypePolicy materialBusinessTypePolicy;

    public DemandPlanItemService(
        DemandPlanItemRepository repository,
        DemandPlanItemMonthRepository monthRepository,
        DemandPlanItemMapper mapper,
        DemandPlanRepository demandPlanRepository,
        DemandPlanningCycleRepository demandPlanningCycleRepository,
        MaterialCatalogGuard materialCatalogGuard,
        SegregationOfDutyRuleService segregationOfDutyRuleService,
        AuditService auditService,
        RoleDataScopeService roleDataScopeService,
        ProcurementMaterialTypeSnapshotService materialTypeSnapshotService,
        MaterialBusinessTypePolicy materialBusinessTypePolicy
    ) {
        this.repository = repository;
        this.monthRepository = monthRepository;
        this.mapper = mapper;
        this.demandPlanRepository = demandPlanRepository;
        this.demandPlanningCycleRepository = demandPlanningCycleRepository;
        this.materialCatalogGuard = materialCatalogGuard;
        this.segregationOfDutyRuleService = segregationOfDutyRuleService;
        this.auditService = auditService;
        this.roleDataScopeService = roleDataScopeService;
        this.materialTypeSnapshotService = materialTypeSnapshotService;
        this.materialBusinessTypePolicy = materialBusinessTypePolicy;
    }

    protected void beforeCreate(DemandPlanItem entity, DemandPlanItemCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        entity.setStatus(DemandPlanItemStatus.DRAFT.name());
        Material material = normalize(entity);
        DemandPlan plan = entity.getDemandPlan() != null ? entity.getDemandPlan() : loadPlan(entity.getDemandPlanId());
        validateCatalogMapping(material, plan);
        materialTypeSnapshotService.applySnapshot(entity, requestType(plan));

        if (!EnumSet.of(DemandPlanStatus.DRAFT, DemandPlanStatus.COLLECTING).contains(DemandPlanStatus.valueOf(plan.getStatus()))) {
            throw new BusinessException("Demand can be added only to DRAFT or COLLECTING plan");
        }
        if (entity.getDepartmentId() == null) {
            entity.setDepartment(plan.getDepartment());
            entity.setDepartmentId(idOf(plan.getDepartment()));
        }
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(idOf(plan.getDepartment()), "Demand plan item");
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(entity.getDepartmentId(), "Demand plan item");
        if (entity.getBudgetItemId() == null) {
            entity.setBudgetItem(plan.getBudgetItem());
        }
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode(plan.getCurrencyCode());
        }
        validateDatesWithinPlan(entity, plan);
    }

    protected void beforeUpdate(DemandPlanItem entity, DemandPlanItemUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        Material material = normalize(entity);
        DemandPlan plan = entity.getDemandPlan() != null ? entity.getDemandPlan() : loadPlan(entity.getDemandPlanId());
        requireEditableParent(plan);
        validateCatalogMapping(material, plan);
        materialTypeSnapshotService.applySnapshot(entity, requestType(plan));
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(idOf(plan.getDepartment()), "Demand plan item");

        roleDataScopeService.assertDepartmentDocumentMutationAllowed(entity.getDepartmentId(), "Demand plan item");
        validateDatesWithinPlan(entity, plan);
    }

    @Transactional
    public DemandPlanItemResponse create(DemandPlanItemCreateRequest dto) {
        DemandPlanItem entity = mapper.toEntity(dto);
        DemandPlan plan = loadPlan(entity.getDemandPlanId());
        List<DemandPlanItemMonthDto> normalizedMonths = normalizeMonthsAndSetQuantities(entity, plan, dto.getMonths());
        beforeCreate(entity, dto);
        DemandPlanItem savedEntity = repository.save(entity);
        saveMonthRows(savedEntity, normalizedMonths);
        auditService.log(entityName, savedEntity.getId(), "CREATE", savedEntity.getCode(), null, null);
        DemandPlanItemResponse saved = mapper.toResponse(savedEntity);
        attachMonths(saved);
        recalculatePlanTotals(saved.getDemandPlanId());
        return saved;
    }

    @Transactional
    public DemandPlanItemResponse update(UUID id, DemandPlanItemUpdateRequest dto) {
        DemandPlanItem current = findEntity(id);
        requireStatus(current, DemandPlanItemStatus.DRAFT);
        dto.setStatus(current.getStatus());
        DemandPlanItem entity = findEntity(id);
        BigDecimal approvedQuantity = entity.getApprovedQuantity();
        BigDecimal reservedQuantity = entity.getReservedQuantity();
        UUID consolidatedDemandId = entity.getConsolidatedDemandId();
        UUID purchaseRequestItemId = entity.getPurchaseRequestItemId();
        mapper.updateEntity(entity, dto);
        entity.setApprovedQuantity(approvedQuantity);
        entity.setReservedQuantity(reservedQuantity);
        entity.setConsolidatedDemandId(consolidatedDemandId);
        entity.setPurchaseRequestItemId(purchaseRequestItemId);
        DemandPlan plan = loadPlan(entity.getDemandPlanId());
        List<DemandPlanItemMonthDto> normalizedMonths = normalizeMonthsAndSetQuantities(entity, plan, dto.getMonths());
        beforeUpdate(entity, dto);
        DemandPlanItem savedEntity = repository.save(entity);
        saveMonthRows(savedEntity, normalizedMonths);
        auditService.log(entityName, savedEntity.getId(), "UPDATE", savedEntity.getCode(), null, null);
        DemandPlanItemResponse saved = mapper.toResponse(savedEntity);
        attachMonths(saved);
        recalculatePlanTotals(saved.getDemandPlanId());
        return saved;
    }

    @Transactional
    public DemandPlanItemResponse submit(UUID id) {
        DemandPlanItem item = findEntity(id);
        requireStatus(item, DemandPlanItemStatus.DRAFT);
        item.setStatus(DemandPlanItemStatus.SUBMITTED.name());
        auditService.log("DemandPlanItem", id, "SUBMIT", item.getCode(), null, null);
        DemandPlanItemResponse saved = mapper.toResponse(repository.save(item));
        attachMonths(saved);
        return saved;
    }

    @Transactional
    public DemandPlanItemResponse approve(UUID id, String comment) {
        DemandPlanItem item = findEntity(id);
        if (!DemandPlanItemStatus.DRAFT.name().equals(item.getStatus()) && !DemandPlanItemStatus.SUBMITTED.name().equals(item.getStatus())) {
            throw new BusinessException("Only DRAFT or SUBMITTED demand item can be approved");
        }
        segregationOfDutyRuleService.validate("DemandPlanItem", "APPROVE", item);
        Material material = normalize(item);
        DemandPlan plan = item.getDemandPlan() != null ? item.getDemandPlan() : loadPlan(item.getDemandPlanId());
        validateCatalogMapping(material, plan);
        materialTypeSnapshotService.applySnapshot(item, requestType(plan));
        if (nvl(item.getApprovedQuantity()).signum() == 0 && nvl(item.getStockFulfillmentQuantity()).signum() == 0) {

            item.setApprovedQuantity(nvl(item.getRequestedQuantity()));
            item.setEstimatedAmount(nvl(item.getApprovedQuantity()).multiply(nvl(item.getEstimatedUnitPrice())));
        }
        item.setStatus(DemandPlanItemStatus.APPROVED.name());
        DemandPlanItem saved = repository.save(item);
        recalculatePlanTotals(saved.getDemandPlanId());
        auditService.log("DemandPlanItem", id, "APPROVE", comment, null, null);
        DemandPlanItemResponse response = mapper.toResponse(saved);
        attachMonths(response);
        return response;
    }

    @Transactional
    public DemandPlanItemResponse reject(UUID id, String comment) {
        DemandPlanItem item = findEntity(id);
        if (!DemandPlanItemStatus.DRAFT.name().equals(item.getStatus()) && !DemandPlanItemStatus.SUBMITTED.name().equals(item.getStatus())) {
            throw new BusinessException("Only DRAFT or SUBMITTED demand item can be rejected");
        }
        item.setStatus(DemandPlanItemStatus.REJECTED.name());
        DemandPlanItem saved = repository.save(item);
        recalculatePlanTotals(saved.getDemandPlanId());
        auditService.log("DemandPlanItem", id, "REJECT", comment, null, null);
        DemandPlanItemResponse response = mapper.toResponse(saved);
        attachMonths(response);
        return response;
    }

    @Transactional
    public DemandPlanItemResponse cancel(UUID id) {
        DemandPlanItem item = findEntity(id);
        if (DemandPlanItemStatus.RESERVED.name().equals(item.getStatus()) || DemandPlanItemStatus.CONVERTED_TO_PR.name().equals(item.getStatus())) {
            throw new BusinessException("Reserved or converted demand item cannot be cancelled");
        }
        item.setStatus(DemandPlanItemStatus.CANCELLED.name());
        DemandPlanItem saved = repository.save(item);
        recalculatePlanTotals(saved.getDemandPlanId());
        auditService.log("DemandPlanItem", id, "CANCEL", item.getCode(), null, null);
        DemandPlanItemResponse response = mapper.toResponse(saved);
        attachMonths(response);
        return response;
    }

    public void recalculatePlanTotals(UUID demandPlanId) {
        if (demandPlanId == null) {
            return;
        }
        DemandPlan plan = loadPlan(demandPlanId);
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal approved = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
        for (DemandPlanItem item : repository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), demandPlanId)) {
            if (DemandPlanItemStatus.CANCELLED.name().equals(item.getStatus()) || DemandPlanItemStatus.REJECTED.name().equals(item.getStatus())) {
                continue;
            }
            planned = planned.add(nvl(item.getEstimatedAmount()));
            if (isApprovedLike(item)) {
                approved = approved.add(nvl(item.getEstimatedAmount()));
            }
            reserved = reserved.add(nvl(item.getReservedQuantity()));
        }
        plan.setTotalPlannedAmount(planned);
        plan.setTotalApprovedAmount(approved);
        plan.setTotalReservedQuantity(reserved);
        demandPlanRepository.save(plan);
        recalculatePlanningCycleTotals(idOf(plan.getPlanningCycle()));
    }

    public void validateForPlanRequestType(DemandPlan plan) {
        for (DemandPlanItem item : repository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), plan.getId())) {
            Material material = materialCatalogGuard.requireUsableMaterial(item.getMaterialId(), "Material");
            validateCatalogMapping(material, plan);
        }

    }

    private List<DemandPlanItemMonthDto> normalizeMonthsAndSetQuantities(DemandPlanItem entity, DemandPlan plan, List<DemandPlanItemMonthDto> incomingMonths) {
        List<DemandPlanItemMonthDto> normalized = new ArrayList<>();
        BigDecimal requestedQty = nvl(entity.getRequestedQuantity());
        if (requestedQty.signum() <= 0) {
            throw new BusinessException("Requested quantity must be positive");
        }
        BigDecimal allocationQty = requestedQty.setScale(0, RoundingMode.DOWN);

        BigDecimal[] quantities = new BigDecimal[12];
        BigDecimal[] approvedQuantities = new BigDecimal[12];
        BigDecimal[] reservedQuantities = new BigDecimal[12];
        for (int index = 0; index < 12; index++) {
            approvedQuantities[index] = BigDecimal.ZERO;
            reservedQuantities[index] = BigDecimal.ZERO;
        }

        if (incomingMonths != null && !incomingMonths.isEmpty()) {
            BigDecimal sumQty = BigDecimal.ZERO;
            for (DemandPlanItemMonthDto dto : incomingMonths) {
                if (dto.getMonth() == null || dto.getMonth() < 1 || dto.getMonth() > 12) {
                    throw new BusinessException("Month must be between 1 and 12");
                }
                BigDecimal q = dto.getQty() == null ? BigDecimal.ZERO : dto.getQty();
                if (q.signum() < 0) {
                    throw new BusinessException("Monthly quantity cannot be negative");
                }
                int index = dto.getMonth() - 1;
                if (quantities[index] != null) {
                    throw new BusinessException("Duplicate month in monthly quantity allocation");
                }
                quantities[index] = q;
                approvedQuantities[index] = dto.getApprovedQty() == null ? BigDecimal.ZERO : dto.getApprovedQty();
                reservedQuantities[index] = dto.getReservedQty() == null ? BigDecimal.ZERO : dto.getReservedQty();
                sumQty = sumQty.add(q);
            }
            if (sumQty.compareTo(allocationQty) > 0) {
                throw new BusinessException("Monthly quantities cannot exceed requested quantity");
            }

            BigDecimal remaining = allocationQty.subtract(sumQty);
            List<Integer> missingMonthIndexes = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                if (quantities[index] == null) {
                    missingMonthIndexes.add(index);
                }
            }

            if (!missingMonthIndexes.isEmpty()) {
                BigDecimal baseQty = remaining.divideToIntegralValue(BigDecimal.valueOf(missingMonthIndexes.size()));
                BigDecimal assigned = baseQty.multiply(BigDecimal.valueOf(missingMonthIndexes.size()));
                BigDecimal remainder = remaining.subtract(assigned);
                for (int pos = 0; pos < missingMonthIndexes.size(); pos++) {
                    int monthIndex = missingMonthIndexes.get(pos);
                    quantities[monthIndex] = baseQty.add(pos == 0 ? remainder : BigDecimal.ZERO);
                }
            } else if (remaining.signum() != 0) {
                quantities[11] = quantities[11].add(remaining);
            }
        } else {
            BigDecimal baseQty = allocationQty.divideToIntegralValue(BigDecimal.valueOf(12));
            BigDecimal assigned = baseQty.multiply(BigDecimal.valueOf(12));
            BigDecimal remainder = allocationQty.subtract(assigned);
            for (int index = 0; index < 12; index++) {
                quantities[index] = baseQty.add(index == 0 ? remainder : BigDecimal.ZERO);
            }
        }

        for (int index = 0; index < 12; index++) {
            BigDecimal qty = quantities[index] == null ? BigDecimal.ZERO : quantities[index];
            normalized.add(new DemandPlanItemMonthDto(index + 1, qty, approvedQuantities[index], reservedQuantities[index]));
        }
        LocalDate derivedRequiredDate = deriveRequiredDate(plan, normalized);
        entity.setRequiredDate(derivedRequiredDate);
        return normalized;
    }

    public LocalDate deriveRequiredDate(DemandPlan plan, List<DemandPlanItemMonthDto> months) {
        int planYear = plan.getPlanYear() != null ? plan.getPlanYear() :
            (plan.getPeriodStart() != null ? plan.getPeriodStart().getYear() : LocalDate.now().getYear());
        Integer earliestMonth = months.stream()
            .filter(m -> m.getQty() != null && m.getQty().signum() > 0)
            .map(DemandPlanItemMonthDto::getMonth)
            .findFirst()
            .orElse(null);

        if (earliestMonth == null) {
            return defaultRequiredDate(plan);
        }

        LocalDate candidate = LocalDate.of(planYear, earliestMonth, 1);
        if (plan.getPeriodStart() != null && candidate.isBefore(plan.getPeriodStart())) {
            return plan.getPeriodStart();
        }
        if (plan.getPeriodEnd() != null && candidate.isAfter(plan.getPeriodEnd())) {
            return plan.getPeriodEnd();
        }
        return candidate;
    }

    private void saveMonthRows(DemandPlanItem savedEntity, List<DemandPlanItemMonthDto> months) {
        List<DemandPlanItemMonth> existing = monthRepository.findByDemandPlanItemIdAndIsDeletedFalseOrderByMonthAsc(savedEntity.getId());
        if (!existing.isEmpty()) {
            existing.forEach(m -> m.setDeleted(true));
            monthRepository.saveAll(existing);
        }
        if (months == null || months.isEmpty()) {
            return;
        }

        for (DemandPlanItemMonthDto dto : months) {
            DemandPlanItemMonth monthEntity = new DemandPlanItemMonth();
            monthEntity.setCode("DPIM-" + UUID.randomUUID().toString().substring(0, 8));
            monthEntity.setName("Demand item month " + dto.getMonth());
            monthEntity.setStatus("ACTIVE");
            monthEntity.setCompanyId(savedEntity.getCompanyId());
            monthEntity.setTenantId(savedEntity.getTenantId());
            monthEntity.setDemandPlanItemId(savedEntity.getId());
            monthEntity.setDemandPlanId(savedEntity.getDemandPlanId());
            monthEntity.setMonth(dto.getMonth());
            monthEntity.setQty(dto.getQty() == null ? BigDecimal.ZERO : dto.getQty());
            monthEntity.setApprovedQty(dto.getApprovedQty() == null ? BigDecimal.ZERO : dto.getApprovedQty());
            monthEntity.setReservedQty(dto.getReservedQty() == null ? BigDecimal.ZERO : dto.getReservedQty());
            monthRepository.save(monthEntity);
        }
    }

    public DemandPlanItemResponse attachMonths(DemandPlanItemResponse dto) {
        if (dto == null || dto.getId() == null) {
            return dto;
        }
        List<DemandPlanItemMonth> months = monthRepository.findByTenantIdAndDemandPlanItemIdAndIsDeletedFalseOrderByMonthAsc(
            TenantContext.currentTenant(), dto.getId());
        dto.setMonths(months.stream()
            .map(m -> new DemandPlanItemMonthDto(m.getMonth(), m.getQty(), m.getApprovedQty(), m.getReservedQty()))
            .toList());
        return dto;
    }

    public List<DemandPlanItemResponse> attachMonthsToResponses(List<DemandPlanItemResponse> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return dtos;
        }
        List<UUID> itemIds = dtos.stream().map(DemandPlanItemResponse::getId).filter(Objects::nonNull).toList();
        if (itemIds.isEmpty()) {
            return dtos;
        }
        Map<UUID, List<DemandPlanItemMonthDto>> map = monthRepository.findByTenantIdAndDemandPlanItemIdInAndIsDeletedFalseOrderByMonthAsc(
            TenantContext.currentTenant(), itemIds
        ).stream().collect(java.util.stream.Collectors.groupingBy(
            DemandPlanItemMonth::getDemandPlanItemId,
            java.util.stream.Collectors.mapping(
                m -> new DemandPlanItemMonthDto(m.getMonth(), m.getQty(), m.getApprovedQty(), m.getReservedQty()),
                java.util.stream.Collectors.toList()
            )
        ));
        dtos.forEach(dto -> dto.setMonths(map.getOrDefault(dto.getId(), List.of())));
        return dtos;
    }

    private UUID idOf(com.enterprise.mm.common.BusinessEntity entity) {
        return entity == null ? null : entity.getId();
    }

    private Material normalize(DemandPlanItem item) {
        if (item.getDemandPlanId() == null) {
            throw new BusinessException("Demand plan is required");
        }
        if (item.getMaterialId() == null) {
            throw new BusinessException("Material is required");
        }
        Material material = materialCatalogGuard.requireUsableMaterial(item.getMaterialId(), "Material");
        if (item.getDemandType() == null || item.getDemandType().isBlank()) {
            item.setDemandType(DemandType.OPERATIONS.name());
        }
        item.setDemandType(DemandType.valueOf(item.getDemandType()).name());
        if (item.getPriority() == null || item.getPriority().isBlank()) {
            item.setPriority(DemandPriority.NORMAL.name());
        }
        item.setPriority(DemandPriority.valueOf(item.getPriority()).name());
        if (DemandType.REPAIR.name().equals(item.getDemandType()) && blank(item.getRepairOrderCode())) {
            throw new BusinessException(
                "Repair order code is required for REPAIR demand",
                "demandPlan.item.repairOrderRequired"
            );
        }
        item.setRequestedQuantity(nvl(item.getRequestedQuantity()));
        if (item.getRequestedQuantity().signum() <= 0) {
            throw new BusinessException("Requested quantity must be positive");
        }
        boolean approvedQuantityDefaulted = item.getApprovedQuantity() == null;
        if (approvedQuantityDefaulted) {
            item.setApprovedQuantity(BigDecimal.ZERO);
        }
        if (item.getApprovedQuantity().signum() < 0) {
            throw new BusinessException("Approved quantity cannot be negative");
        }
        if (item.getApprovedQuantity().compareTo(item.getRequestedQuantity()) > 0) {
            throw new BusinessException("Approved quantity cannot exceed requested quantity");
        }
        item.setReservedQuantity(nvl(item.getReservedQuantity()));
        if (item.getReservedQuantity().signum() < 0) {
            throw new BusinessException("Reserved quantity cannot be negative");
        }
        if (item.getReservedQuantity().compareTo(item.getRequestedQuantity()) > 0) {
            throw new BusinessException("Reserved quantity cannot exceed requested quantity");
        }
        item.setEstimatedUnitPrice(nvl(item.getEstimatedUnitPrice()));
        BigDecimal estimatedQuantity = approvedQuantityDefaulted ? item.getRequestedQuantity() : item.getApprovedQuantity();
        item.setEstimatedAmount(estimatedQuantity.multiply(item.getEstimatedUnitPrice()));
        if (item.getCurrencyCode() == null || item.getCurrencyCode().isBlank()) {
            item.setCurrencyCode("UZS");
        }
        return material;
    }

    private void validateCatalogMapping(Material material, DemandPlan plan) {
        RequestType requestType = plan.getRequestType() == null ? RequestType.GOODS : plan.getRequestType();
        com.enterprise.mm.enums.MaterialType materialType =
            materialBusinessTypePolicy.normalizeMaterialType(material.getMaterialType(), material.getMxik());
        materialBusinessTypePolicy.snapshotForMaterial(
            material,
            materialType == com.enterprise.mm.enums.MaterialType.SERVICES
                ? com.enterprise.mm.enums.ProcurementLineType.SERVICE
                : com.enterprise.mm.enums.ProcurementLineType.MATERIAL,
            requestType,
            "demandPlanItem"
        );
        String mxikType = material.getMxik() == null ? null : material.getMxik().getType();
        if (requestType.isServiceStyle() && mxikType != null && !RequestType.SERVICE.name().equalsIgnoreCase(mxikType)) {
            throw new BusinessException(requestType + " demand plans require material MXIK type SERVICE");
        }
        if (requestType == RequestType.GOODS && mxikType != null && !RequestType.GOODS.name().equalsIgnoreCase(mxikType)) {
            throw new BusinessException("GOODS demand plans require material MXIK type GOODS or no MXIK classification");
        }
    }



    private RequestType requestType(DemandPlan plan) {
        return plan.getRequestType() == null ? RequestType.GOODS : plan.getRequestType();
    }

    private void validateDatesWithinPlan(DemandPlanItem item, DemandPlan plan) {
        LocalDate needBy = com.enterprise.mm.common.DataQualityGuards.requireSaneDate(item.getNeedByDate(), "needByDate");
        if (needBy != null && (
            (plan.getPeriodStart() != null && needBy.isBefore(plan.getPeriodStart()))
            || (plan.getPeriodEnd() != null && needBy.isAfter(plan.getPeriodEnd()))
            || (plan.getPlanYear() != null && needBy.getYear() != plan.getPlanYear())
        )) {
            throw new BusinessException(
                "Need-by date must be inside the demand plan period and year",
                "demandPlanItem.needByDate.outsidePeriod"
            );
        }
        if (plan.getPeriodStart() == null || plan.getPeriodEnd() == null) {
            return;
        }
        if (item.getRequiredDate() != null && (item.getRequiredDate().isBefore(plan.getPeriodStart()) || item.getRequiredDate().isAfter(plan.getPeriodEnd()))) {
            throw new BusinessException("Required date must be inside demand plan period");
        }
    }

    private LocalDate defaultRequiredDate(DemandPlan plan) {
        LocalDate candidate = LocalDate.now().plusDays(30);
        if (plan.getPeriodStart() == null || plan.getPeriodEnd() == null) {
            return candidate;
        }
        if (candidate.isBefore(plan.getPeriodStart())) {
            return plan.getPeriodStart();
        }
        if (candidate.isAfter(plan.getPeriodEnd())) {
            return plan.getPeriodEnd();
        }
        return candidate;
    }

    private void recalculatePlanningCycleTotals(UUID planningCycleId) {
        if (planningCycleId == null) {
            return;
        }
        DemandPlanningCycle cycle = demandPlanningCycleRepository.findByIdAndIsDeletedFalse(planningCycleId)
            .filter(existing -> !existing.isDeleted() && TenantContext.currentTenant().equals(existing.getTenantId()))
            .orElse(null);
        if (cycle == null) {
            return;
        }
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal approved = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
        int itemCount = 0;
        var plans = demandPlanRepository.findByTenantIdAndPlanningCycle_IdAndIsDeletedFalse(TenantContext.currentTenant(), planningCycleId);
        for (DemandPlan plan : plans) {
            planned = planned.add(nvl(plan.getTotalPlannedAmount()));
            approved = approved.add(nvl(plan.getTotalApprovedAmount()));
            reserved = reserved.add(nvl(plan.getTotalReservedQuantity()));
            itemCount += repository.findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(TenantContext.currentTenant(), plan.getId()).size();
        }
        cycle.setTotalPlanCount(plans.size());
        cycle.setTotalItemCount(itemCount);
        cycle.setTotalPlannedAmount(planned);
        cycle.setTotalApprovedAmount(approved);
        cycle.setTotalReservedQuantity(reserved);
        demandPlanningCycleRepository.save(cycle);
    }

    private DemandPlan loadPlan(UUID id) {
        return demandPlanRepository.findByIdAndIsDeletedFalse(id)
            .filter(plan -> !plan.isDeleted() && TenantContext.currentTenant().equals(plan.getTenantId()))
            .orElseThrow(() -> new BusinessException("Demand plan not found: " + id));
    }

    private void requireEditableParent(DemandPlan plan) {
        if (!DemandPlanStatus.DRAFT.name().equals(plan.getStatus())
            && !DemandPlanStatus.COLLECTING.name().equals(plan.getStatus())) {
            throw new BusinessException("Demand item parent demand plan must be DRAFT or COLLECTING, current status is " + plan.getStatus());
        }
    }

    private void requireStatus(DemandPlanItem item, DemandPlanItemStatus status) {
        if (!status.name().equals(item.getStatus())) {
            throw new BusinessException("Demand item must be " + status + ", current status is " + item.getStatus());
        }
    }

    private boolean isApprovedLike(DemandPlanItem item) {
        return DemandPlanItemStatus.APPROVED.name().equals(item.getStatus())
            || DemandPlanItemStatus.RESERVED.name().equals(item.getStatus())
            || DemandPlanItemStatus.CONVERTED_TO_PR.name().equals(item.getStatus());
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Transactional(readOnly = true)
    public Page<DemandPlanItemResponse> search(DemandPlanItemSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var pageRequest = dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable;
        var spec = ProcurementSearchSpecifications.build(
            DemandPlanItem.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        var records = repository.findAll(spec, pageRequest);
        if (!dataScope.hasDocumentScope()) {
            Page<DemandPlanItemResponse> mapped = records.map(mapper::toResponse);
            attachMonthsToResponses(mapped.getContent());
            return mapped;
        }
        Page<DemandPlanItemResponse> filtered = roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, entity.getDepartmentId()),
            mapper::toResponse
        );
        attachMonthsToResponses(filtered.getContent());
        return filtered;
    }

    @Transactional(readOnly = true)
    public DemandPlanItemResponse get(UUID id) {
        DemandPlanItemResponse response = mapper.toResponse(findEntity(id));
        attachMonths(response);
        return response;
    }

    @Transactional
    public void softDelete(UUID id) {
        DemandPlanItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        List<DemandPlanItemMonth> months = monthRepository.findByDemandPlanItemIdAndIsDeletedFalseOrderByMonthAsc(id);
        if (!months.isEmpty()) {
            months.forEach(m -> m.setDeleted(true));
            monthRepository.saveAll(months);
        }
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }


    @Transactional
    public DemandPlanItemResponse restore(UUID id) {
        DemandPlanItem entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, entity.getDepartmentId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        DemandPlanItem saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        DemandPlanItemResponse response = mapper.toResponse(saved);
        attachMonths(response);
        return response;
    }

    protected DemandPlanItem findEntity(UUID id) {
        DemandPlanItem entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, entity.getDepartmentId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    protected boolean companyScoped() {
        return true;
    }
}

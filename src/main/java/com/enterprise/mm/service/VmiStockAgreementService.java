package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ConsignmentStockTransactionResponse;
import com.enterprise.mm.payload.InventoryPlanningDtos.ConsignmentConsumptionRequest;
import com.enterprise.mm.payload.InventoryPlanningDtos.VmiReviewResult;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.VmiStockAgreementResponse;
import com.enterprise.mm.payload.VmiStockAgreementUpdateRequest;
import com.enterprise.mm.payload.VmiStockAgreementCreateRequest;
import com.enterprise.mm.payload.VmiStockAgreementSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ConsignmentStockTransactionMapper;
import com.enterprise.mm.mapper.VmiStockAgreementMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ConsignmentTransactionType;
import com.enterprise.mm.enums.VmiAgreementStatus;
import com.enterprise.mm.enums.VmiStockType;
import com.enterprise.mm.entity.ConsignmentStockTransaction;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.VmiStockAgreement;
import com.enterprise.mm.repository.ConsignmentStockTransactionRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.VmiStockAgreementRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VmiStockAgreementService {
    private final VmiStockAgreementRepository repository;
    private final VmiStockAgreementMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final String entityName = "VmiStockAgreement";
    private final StockBalanceRepository stockBalanceRepository;
    private final ConsignmentStockTransactionRepository consignmentTransactionRepository;
    private final ConsignmentStockTransactionMapper consignmentTransactionMapper;
    private final RoleDataScopeService roleDataScopeService;
    private final IntegrationEventService integrationEventService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    protected void beforeCreate(VmiStockAgreement entity, VmiStockAgreementCreateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
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

        if (entity.getSupplierId() == null || entity.getMaterialId() == null || entity.getWarehouseId() == null) {
            throw new BusinessException("Supplier, material and warehouse are required for VMI agreement");
        }
        if (entity.getStockType() == null || entity.getStockType().isBlank()) {
            entity.setStockType(VmiStockType.VMI.name());
        }
        entity.setStatus(VmiAgreementStatus.ACTIVE.name());
        entity.setMinStock(nvl(entity.getMinStock()));
        entity.setReorderPoint(nvl(entity.getReorderPoint()));
        entity.setTargetStock(nvl(entity.getTargetStock()));
        entity.setMaxStock(nvl(entity.getMaxStock()));
    }
    @Transactional
    public VmiReviewResult review(UUID id) {
        VmiStockAgreement agreement = findEntity(id);
        materialCatalogGuard.requireActiveTmbMaterial(agreement.getMaterialId(), "materialId");
        if (!VmiAgreementStatus.ACTIVE.name().equals(agreement.getStatus())) {
            throw new BusinessException("Only ACTIVE VMI agreement can be reviewed");
        }
        BigDecimal available = stockBalanceRepository.findCompanyBalance(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            agreement.getMaterialId(),
            agreement.getWarehouseId(),
            agreement.getStorageLocationId() == null,
            agreement.getStorageLocationId(),
            true,
            null
        ).map(StockBalance::getAvailableQuantity).orElse(BigDecimal.ZERO);
        BigDecimal reorderPoint = nvl(agreement.getReorderPoint());
        BigDecimal targetStock = nvl(agreement.getTargetStock()).max(reorderPoint);
        BigDecimal recommended = targetStock.subtract(available).max(BigDecimal.ZERO);
        IntegrationEvent event = null;
        boolean replenishmentRequired = available.compareTo(reorderPoint) <= 0 && recommended.signum() > 0;
        agreement.setLastReviewedAt(LocalDateTime.now());
        if (replenishmentRequired) {
            agreement.setLastReplenishmentRequestAt(LocalDateTime.now());
            event = integrationEventService.emit("VMI_REPLENISHMENT_REQUIRED", "VmiStockAgreement", agreement.getId(), Map.of(
                "agreementId", agreement.getId(),
                "supplierId", agreement.getSupplierId(),
                "materialId", agreement.getMaterialId(),
                "warehouseId", agreement.getWarehouseId(),
                "availableQuantity", available,
                "recommendedQuantity", recommended,
                "stockType", agreement.getStockType()
            ));
            createVmiReplenishmentNotification(agreement, available, recommended);
        }
        VmiStockAgreement saved = repository.save(agreement);
        auditService.log("VmiStockAgreement", id, "REVIEW", replenishmentRequired ? "REPLENISH" : "OK", null, null);
        return new VmiReviewResult(
            saved.getId(),
            saved.getSupplierId(),
            saved.getMaterialId(),
            saved.getWarehouseId(),
            available,
            reorderPoint,
            targetStock,
            recommended,
            replenishmentRequired,
            event == null ? null : event.getId()
        );
    }

    private void createVmiReplenishmentNotification(
        VmiStockAgreement agreement,
        BigDecimal available,
        BigDecimal recommended
    ) {
        notificationService.createForUsernameOrRole(
            "MM_MANAGER",
            "notification.vmi.replenishment.title",
            "notification.vmi.replenishment.message",
            Map.of(
                "materialId", agreement.getMaterialId(),
                "available", available,
                "recommended", recommended
            ),
            null
        );
    }

    @Transactional
    public List<VmiReviewResult> reviewAll() {
        List<VmiReviewResult> results = new ArrayList<>();
        for (VmiStockAgreement agreement : repository.findByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            VmiAgreementStatus.ACTIVE.name()
        )) {
            results.add(review(agreement.getId()));
        }
        return results;
    }
    @Transactional
    public ConsignmentStockTransactionResponse consumeConsignment(UUID id, ConsignmentConsumptionRequest request) {
        VmiStockAgreement agreement = findEntity(id);
        materialCatalogGuard.requireActiveTmbMaterial(agreement.getMaterialId(), "materialId");
        if (!VmiStockType.CONSIGNMENT.name().equals(agreement.getStockType())) {
            throw new BusinessException("Only CONSIGNMENT agreement can register consumption");
        }
        BigDecimal quantity = request == null ? BigDecimal.ZERO : nvl(request.quantity());
        if (quantity.signum() <= 0) {
            throw new BusinessException("Consumption quantity must be positive");
        }
        BigDecimal unitCost = request == null || request.unitCost() == null ? BigDecimal.ZERO : request.unitCost();
        ConsignmentStockTransaction transaction = new ConsignmentStockTransaction();
        transaction.setCode("CONS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        transaction.setName("Consignment consumption");
        transaction.setStatus("OPEN");
        transaction.setVmiAgreementId(agreement.getId());
        transaction.setSupplierId(agreement.getSupplierId());
        transaction.setMaterialId(agreement.getMaterialId());
        transaction.setWarehouseId(agreement.getWarehouseId());
        transaction.setStorageLocationId(agreement.getStorageLocationId());
        transaction.setStockMovementId(request == null ? null : request.stockMovementId());
        transaction.setTransactionType(ConsignmentTransactionType.CONSUMPTION.name());
        transaction.setQuantity(quantity);
        transaction.setUnitCost(unitCost);
        transaction.setAmount(quantity.multiply(unitCost));
        transaction.setSettlementStatus("PENDING");
        transaction.setSettlementReference(request == null ? null : request.settlementReference());
        transaction.setTransactionAt(LocalDateTime.now());
        ConsignmentStockTransaction saved = consignmentTransactionRepository.save(transaction);
        integrationEventService.emit("CONSIGNMENT_CONSUMED", "ConsignmentStockTransaction", saved.getId(), Map.of(
            "agreementId", agreement.getId(),
            "supplierId", agreement.getSupplierId(),
            "materialId", agreement.getMaterialId(),
            "quantity", quantity,
            "amount", saved.getAmount()
        ));
        auditService.log("ConsignmentStockTransaction", saved.getId(), "CONSUME", saved.getAmount().toPlainString(), null, null);
        return consignmentTransactionMapper.toResponse(saved);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<VmiStockAgreementResponse> search(VmiStockAgreementSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(),
            filter.supplierId(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public VmiStockAgreementResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public VmiStockAgreementResponse create(VmiStockAgreementCreateRequest dto) {
        VmiStockAgreement entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        VmiStockAgreement saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public VmiStockAgreementResponse update(UUID id, VmiStockAgreementUpdateRequest dto) {
        VmiStockAgreement entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        VmiStockAgreement saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        VmiStockAgreement entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public VmiStockAgreementResponse restore(UUID id) {
        VmiStockAgreement entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        VmiStockAgreement saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected VmiStockAgreement findEntity(UUID id) {
        VmiStockAgreement entity = repository.findByIdAndIsDeletedFalse(id)
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
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        return entity;
    }
    protected void beforeUpdate(VmiStockAgreement entity, VmiStockAgreementUpdateRequest dto) {
        roleDataScopeService.assertWarehouseAccess(entity.getWarehouseId());
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}

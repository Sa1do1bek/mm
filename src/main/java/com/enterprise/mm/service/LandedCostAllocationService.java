package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.LandedCostAllocationResponse;
import com.enterprise.mm.payload.LandedCostAllocationUpdateRequest;
import com.enterprise.mm.payload.LandedCostAllocationCreateRequest;
import com.enterprise.mm.payload.LandedCostAllocationSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.LandedCostAllocationMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.LandedCostAllocation;
import com.enterprise.mm.entity.LandedCostAllocationLine;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.LandedCostAllocationLineRepository;
import com.enterprise.mm.repository.LandedCostAllocationRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LandedCostAllocationService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final String entityName = "LandedCostAllocation";
    private static final int MONEY_SCALE = 4;
    private final LandedCostAllocationRepository repository;
    private final LandedCostAllocationMapper mapper;
    private final LandedCostAllocationLineRepository lineRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final StockBatchRepository stockBatchRepository;
    private final ImportDeliveryRepository importDeliveryRepository;
    private final InventoryCostingService inventoryCostingService;
    private final FinancialAuthorizationService financialAuthorizationService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final MaterialCatalogGuard materialCatalogGuard;

    protected void beforeCreate(LandedCostAllocation entity, LandedCostAllocationCreateRequest dto) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        entity.setStatus("DRAFT");
        normalize(entity);
    }
    protected void beforeUpdate(LandedCostAllocation entity, LandedCostAllocationUpdateRequest dto) {
        if (!"DRAFT".equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT landed cost allocation can be edited");
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
    }
    @Transactional
    public LandedCostAllocationResponse post(UUID id) {
        financialAuthorizationService.requireFinance("post landed cost");
        LandedCostAllocation document = findEntity(id);
        guardOpeningImport(document.getCompanyId());
        if (!"DRAFT".equals(document.getStatus())) {
            throw new BusinessException("Only DRAFT landed cost allocation can be posted");
        }
        List<LandedCostAllocationLine> lines = lineRepository.findByTenantIdAndCompanyIdAndAllocationDocumentIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            document.getId()
        );
        if (lines.isEmpty()) {
            throw new BusinessException("Landed cost allocation must have at least one line");
        }
        allocateAmountsIfNeeded(document, lines);
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal baseTotalAmount = BigDecimal.ZERO;
        for (LandedCostAllocationLine line : lines) {
            materialCatalogGuard.requireActiveTmbMaterial(line.getMaterialId(), "materialId");
            BigDecimal baseAmount = nvl(line.getAllocatedBaseAmount());
            if (baseAmount.signum() <= 0) {
                throw new BusinessException("Allocated base amount must be positive");
            }
            StockBalance balance = stockBalanceRepository.findCompanyBalanceForUpdate(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    line.getMaterialId(),
                    line.getWarehouseId(),
                    line.getStorageLocationId() == null,
                    line.getStorageLocationId(),
                    line.getStockBatchId() == null,
                    line.getStockBatchId()
                )
                .orElseThrow(() -> new BusinessException("Stock balance not found for landed cost allocation line"));
            guardOpeningImport(balance.getCompanyId());
            BigDecimal quantity = nvl(line.getQuantity()).signum() > 0 ? line.getQuantity() : nvl(balance.getQuantity());
            if (quantity.signum() <= 0) {
                throw new BusinessException("Stock balance quantity must be positive for landed cost capitalization");
            }
            line.setQuantity(quantity);
            line.setUnitCostIncrease(baseAmount.divide(quantity, 6, RoundingMode.HALF_UP));
            balance.setTotalValue(nvl(balance.getTotalValue()).add(baseAmount));
            stockBalanceRepository.save(balance);
            List<InventoryCostingService.LandedCostAdjustment> adjustments = inventoryCostingService.capitalizeLandedCost(
                line.getMaterialId(),
                line.getWarehouseId(),
                line.getStockBatchId(),
                baseAmount,
                "LANDED_COST_ALLOCATION",
                document.getId()
            );
            updateBatchFinalCosts(adjustments);
            lineRepository.save(line);
            totalAmount = totalAmount.add(nvl(line.getAllocatedAmount()));
            baseTotalAmount = baseTotalAmount.add(baseAmount);
        }
        document.setTotalAmount(totalAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        document.setBaseTotalAmount(baseTotalAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        document.setStatus("POSTED");
        document.setPostedAt(LocalDateTime.now());
        LandedCostAllocation saved = repository.save(document);
        integrationEventService.emit("INVENTORY.LANDED_COST_CAPITALIZED", "LandedCostAllocation", saved.getId(), Map.of(
            "importDeliveryId", saved.getImportDeliveryId() == null ? "" : saved.getImportDeliveryId(),
            "currencyCode", saved.getCurrencyCode() == null ? "UZS" : saved.getCurrencyCode(),
            "baseCurrencyCode", saved.getBaseCurrencyCode() == null ? "UZS" : saved.getBaseCurrencyCode(),
            "totalAmount", saved.getTotalAmount() == null ? BigDecimal.ZERO : saved.getTotalAmount(),
            "baseTotalAmount", saved.getBaseTotalAmount() == null ? BigDecimal.ZERO : saved.getBaseTotalAmount(),
            "lineCount", lines.size()
        ));
        auditService.log("LandedCostAllocation", saved.getId(), "POST", saved.getCode(), null, null);
        return mapper.toResponse(saved);
    }

    private void updateBatchFinalCosts(List<InventoryCostingService.LandedCostAdjustment> adjustments) {
        Map<UUID, BatchCostAdjustment> byBatch = new java.util.LinkedHashMap<>();
        for (InventoryCostingService.LandedCostAdjustment adjustment : adjustments) {
            if (adjustment.stockBatchId() == null) {
                continue;
            }
            byBatch.computeIfAbsent(adjustment.stockBatchId(), ignored -> new BatchCostAdjustment())
                .add(adjustment.quantity(), adjustment.totalCostIncrease());
        }
        for (Map.Entry<UUID, BatchCostAdjustment> entry : byBatch.entrySet()) {
            BatchCostAdjustment adjustment = entry.getValue();
            if (adjustment.quantity.signum() <= 0 || adjustment.amount.signum() <= 0) {
                continue;
            }
            BigDecimal unitCostIncrease = adjustment.amount.divide(adjustment.quantity, 6, RoundingMode.HALF_UP);
            stockBatchRepository.findByIdAndIsDeletedFalse(entry.getKey()).ifPresent(batch -> {
                if (!TenantContext.currentTenant().equals(batch.getTenantId())
                    || (companyScoped() && !com.enterprise.mm.security.AccessScope.canAccessCompany(batch.getCompanyId()))) {
                    return;
                }
                BigDecimal purchaseCost = nvl(batch.getPurchaseUnitCost()).signum() > 0
                    ? nvl(batch.getPurchaseUnitCost())
                    : nvl(batch.getUnitCost());
                BigDecimal additionalCost = nvl(batch.getAdditionalCostPerUnit()).add(nvl(unitCostIncrease));
                batch.setPurchaseUnitCost(purchaseCost);
                batch.setAdditionalCostPerUnit(additionalCost);
                batch.setFinalUnitCost(purchaseCost.add(additionalCost));
                stockBatchRepository.save(batch);
            });
        }
    }

    private static final class BatchCostAdjustment {
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;

        private void add(BigDecimal nextQuantity, BigDecimal nextAmount) {
            quantity = quantity.add(nextQuantity == null ? BigDecimal.ZERO : nextQuantity);
            amount = amount.add(nextAmount == null ? BigDecimal.ZERO : nextAmount);
        }
    }

    private void normalize(LandedCostAllocation entity) {
        validateImportDelivery(entity.getImportDeliveryId());
        if (entity.getDocumentDate() == null) {
            entity.setDocumentDate(LocalDate.now());
        }
        entity.setAllocationMethod(normalizeToken(entity.getAllocationMethod(), "VALUE"));
        if (!List.of("VALUE", "QUANTITY", "WEIGHT", "MANUAL").contains(entity.getAllocationMethod())) {
            throw new BusinessException("Unsupported landed cost allocation method: " + entity.getAllocationMethod());
        }
        entity.setCurrencyCode(normalizeToken(entity.getCurrencyCode(), "UZS"));
        entity.setBaseCurrencyCode(normalizeToken(entity.getBaseCurrencyCode(), "UZS"));
        entity.setExchangeRate(nvl(entity.getExchangeRate()).signum() <= 0 ? BigDecimal.ONE : entity.getExchangeRate());
        entity.setTotalAmount(nvl(entity.getTotalAmount()));
        entity.setBaseTotalAmount(nvl(entity.getBaseTotalAmount()));
        if (entity.getBaseTotalAmount().signum() == 0 && entity.getTotalAmount().signum() > 0) {
            entity.setBaseTotalAmount(entity.getTotalAmount().multiply(entity.getExchangeRate()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }
    }
    private void allocateAmountsIfNeeded(LandedCostAllocation document, List<LandedCostAllocationLine> lines) {
        lines.forEach(line -> {
            if (nvl(line.getAllocationBase()).signum() == 0) {
                if ("QUANTITY".equals(document.getAllocationMethod())) {
                    line.setAllocationBase(nvl(line.getQuantity()));
                } else if (nvl(line.getQuantity()).signum() > 0) {
                    line.setAllocationBase(line.getQuantity());
                }
            }
            line.setAllocatedAmount(nvl(line.getAllocatedAmount()));
            line.setAllocatedBaseAmount(nvl(line.getAllocatedBaseAmount()));
            line.setAllocationBase(nvl(line.getAllocationBase()));
        });
        BigDecimal amountSum = lines.stream().map(LandedCostAllocationLine::getAllocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal baseAmountSum = lines.stream().map(LandedCostAllocationLine::getAllocatedBaseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal allocationBaseSum = lines.stream().map(LandedCostAllocationLine::getAllocationBase).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocationBaseSum.signum() == 0 && !lines.isEmpty()) {
            lines.forEach(line -> line.setAllocationBase(BigDecimal.ONE));
            allocationBaseSum = BigDecimal.valueOf(lines.size());
        }
        if (amountSum.signum() == 0 && nvl(document.getTotalAmount()).signum() > 0 && allocationBaseSum.signum() > 0) {
            BigDecimal remainder = document.getTotalAmount();
            for (int i = 0; i < lines.size(); i++) {
                LandedCostAllocationLine line = lines.get(i);
                BigDecimal allocated = i == lines.size() - 1
                    ? remainder
                    : document.getTotalAmount().multiply(line.getAllocationBase()).divide(allocationBaseSum, MONEY_SCALE, RoundingMode.HALF_UP);
                line.setAllocatedAmount(allocated);
                remainder = remainder.subtract(allocated);
            }
        }
        if (baseAmountSum.signum() == 0) {
            BigDecimal exchangeRate = nvl(document.getExchangeRate()).signum() <= 0 ? BigDecimal.ONE : document.getExchangeRate();
            lines.forEach(line -> line.setAllocatedBaseAmount(line.getAllocatedAmount().multiply(exchangeRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP)));
        }
    }
    private void validateImportDelivery(UUID importDeliveryId) {
        if (importDeliveryId == null) {
            throw new BusinessException("Import delivery is required");
        }
        ImportDelivery delivery = importDeliveryRepository.findByIdAndIsDeletedFalse(importDeliveryId)
            .orElseThrow(() -> new BusinessException("Import delivery not found: " + importDeliveryId));
        if (delivery.isDeleted() || !TenantContext.currentTenant().equals(delivery.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(delivery.getCompanyId())) {
            throw new BusinessException("Import delivery not found: " + importDeliveryId);
        }
    }
    private String normalizeToken(String value, String fallback) {
        return (value == null || value.isBlank() ? fallback : value).trim().toUpperCase(Locale.ROOT);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<LandedCostAllocationResponse> search(LandedCostAllocationSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
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
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public LandedCostAllocationResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public LandedCostAllocationResponse create(LandedCostAllocationCreateRequest dto) {
        LandedCostAllocation entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        LandedCostAllocation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public LandedCostAllocationResponse update(UUID id, LandedCostAllocationUpdateRequest dto) {
        LandedCostAllocation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutable(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        LandedCostAllocation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        LandedCostAllocation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutable(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public LandedCostAllocationResponse restore(UUID id) {
        LandedCostAllocation entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutable(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        LandedCostAllocation saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    private void requireMutable(LandedCostAllocation entity) {
        if ("POSTED".equals(entity.getStatus())) {
            throw new BusinessException("POSTED landed cost allocation is immutable; create a reversal or correction");
        }
    }
    protected LandedCostAllocation findEntity(UUID id) {
        LandedCostAllocation entity = repository.findByIdAndIsDeletedFalse(id)
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
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }
}

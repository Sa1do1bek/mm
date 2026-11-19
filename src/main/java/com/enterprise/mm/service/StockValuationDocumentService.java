package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.StockValuationDocumentResponse;
import com.enterprise.mm.payload.StockValuationDocumentUpdateRequest;
import com.enterprise.mm.payload.StockValuationDocumentCreateRequest;
import com.enterprise.mm.payload.StockValuationDocumentSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.StockValuationDocumentMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.RevaluationPostingMode;
import com.enterprise.mm.enums.NonMovingStockExecutionOutputStatus;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.entity.StockValuationDocument;
import com.enterprise.mm.repository.StockValuationDocumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockValuationDocumentService {
    private final String entityName = "StockValuationDocument";
    private final StockValuationDocumentRepository repository;
    private final StockValuationDocumentMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final StockValuationService valuationService;
    private final InventoryCostingService inventoryCostingService;
    private final InventoryValuationPolicyService valuationPolicyService;
    private final FinancialAuthorizationService financialAuthorizationService;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final NonMovingStockExecutionService nonMovingStockExecutionService;
    protected void beforeCreate(StockValuationDocument entity, StockValuationDocumentCreateRequest dto) {
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

        entity.setStatus("DRAFT");
        normalize(entity);
    }
    protected void beforeUpdate(StockValuationDocument entity, StockValuationDocumentUpdateRequest dto) {
        materialCatalogGuard.requireActiveTmbMaterial(entity.getMaterialId(), "materialId");
        if (!"DRAFT".equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT valuation document can be edited");
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
    }
    @Transactional
    public StockValuationDocumentResponse approve(UUID id) {
        financialAuthorizationService.requireFinance("approve valuation document");
        StockValuationDocument document = findEntity(id);
        if (!java.util.Set.of("DRAFT", "PROPOSED", "UNDER_REVIEW").contains(document.getStatus())) {
            throw new BusinessException("Only DRAFT, PROPOSED or UNDER_REVIEW valuation document can be approved");
        }
        if (document.getReason() == null || document.getReason().isBlank()) {
            throw new BusinessException("Valuation document approval requires a reason");
        }
        document.setStatus("APPROVED");
        StockValuationDocument saved = repository.save(document);
        auditService.log("StockValuationDocument", saved.getId(), "APPROVE", saved.getDocumentType(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public StockValuationDocumentResponse propose(UUID id) {
        StockValuationDocument document = findEntity(id);
        if (!java.util.Set.of("DRAFT", "RISK_DETECTED").contains(document.getStatus())) {
            throw new BusinessException("Only DRAFT or RISK_DETECTED impairment can be proposed");
        }
        document.setStatus("PROPOSED");
        StockValuationDocument saved = repository.save(document);
        auditService.log("StockValuationDocument", saved.getId(), "PROPOSE", saved.getDocumentType(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public StockValuationDocumentResponse review(UUID id) {
        financialAuthorizationService.requireFinance("review impairment");
        StockValuationDocument document = findEntity(id);
        if (!"PROPOSED".equals(document.getStatus())) {
            throw new BusinessException("Only PROPOSED impairment can be reviewed");
        }
        document.setStatus("UNDER_REVIEW");
        StockValuationDocument saved = repository.save(document);
        auditService.log("StockValuationDocument", saved.getId(), "REVIEW", saved.getDocumentType(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public StockValuationDocumentResponse reject(UUID id) {
        financialAuthorizationService.requireFinance("reject impairment");
        StockValuationDocument document = findEntity(id);
        if ("POSTED".equals(document.getStatus())) {
            throw new BusinessException("Posted impairment cannot be rejected");
        }
        document.setStatus("REJECTED");
        StockValuationDocument saved = repository.save(document);
        auditService.log("StockValuationDocument", saved.getId(), "REJECT", saved.getDocumentType(), null, null);
        nonMovingStockExecutionService.reconcileDocumentOutput(
            "STOCK_VALUATION_DOCUMENT",
            saved.getId(),
            NonMovingStockExecutionOutputStatus.CANCELLED,
            "STOCK_VALUATION_DOCUMENT_REJECTED",
            "Stock valuation document was rejected"
        );
        return mapper.toResponse(saved);
    }

    @Transactional
    public StockValuationDocumentResponse post(UUID id) {
        financialAuthorizationService.requireFinance("post valuation document");
        StockValuationDocument document = findEntity(id);
        if (!"APPROVED".equals(document.getStatus())) {
            throw new BusinessException("Only APPROVED valuation document can be posted");
        }
        ValuationMethod method = method(document);
        String type = document.getDocumentType().toUpperCase(Locale.ROOT);
        if ("REVALUATION".equals(type)) {
            BigDecimal newCost = nvl(document.getNewUnitCost());
            if (newCost.signum() <= 0) {
                throw new BusinessException("New unit cost is required for revaluation");
            }
            BigDecimal oldCost = valuationService.recalculate(document.getMaterialId(), document.getWarehouseId(), method).getUnitCost();
            var valuation = valuationService.applyRevaluation(document.getMaterialId(), document.getWarehouseId(), method, newCost, document.getReason());
            document.setQuantity(valuation.getQuantity());
            document.setOldUnitCost(nvl(document.getOldUnitCost()).signum() == 0 ? oldCost : document.getOldUnitCost());
            document.setAmount(valuation.getRevaluationAmount());
        } else if ("IMPAIRMENT_RESERVE".equals(type)) {
            var valuation = valuationService.applyImpairmentReserve(document.getMaterialId(), document.getWarehouseId(), method, document.getReserveAmount(), document.getReason());
            document.setQuantity(valuation.getQuantity());
            document.setAmount(valuation.getImpairmentReserve());
            RevaluationPostingMode postingMode = valuationPolicyService.impairmentPostingMode();
            boolean updateCarryingCost = postingMode == RevaluationPostingMode.UPDATE_STOCK_BALANCES;
            inventoryCostingService.postImpairmentReserve(
                document.getMaterialId(),
                document.getWarehouseId(),
                valuation.getImpairmentReserve(),
                updateCarryingCost,
                "STOCK_VALUATION_DOCUMENT",
                document.getId()
            );
            if (updateCarryingCost) {
                valuationService.reduceStockBalanceValueForImpairment(document.getMaterialId(), document.getWarehouseId(), valuation.getImpairmentReserve());
            }
        } else {
            throw new BusinessException("Unsupported valuation document type: " + document.getDocumentType());
        }
        document.setStatus("POSTED");
        document.setPostedAt(LocalDateTime.now());
        StockValuationDocument saved = repository.save(document);
        auditService.log("StockValuationDocument", saved.getId(), "POST", saved.getDocumentType(), null, null);
        nonMovingStockExecutionService.reconcileDocumentOutput(
            "STOCK_VALUATION_DOCUMENT",
            saved.getId(),
            NonMovingStockExecutionOutputStatus.POSTED,
            null,
            null
        );
        return mapper.toResponse(saved);
    }
    private void normalize(StockValuationDocument entity) {
        if (entity.getDocumentType() == null || entity.getDocumentType().isBlank()) {
            entity.setDocumentType("REVALUATION");
        }
        if (entity.getValuationMethod() == null) {
            entity.setValuationMethod(ValuationMethod.ACTUAL_COST);
        }
        if (entity.getDocumentDate() == null) {
            entity.setDocumentDate(LocalDate.now());
        }
        entity.setQuantity(nvl(entity.getQuantity()));
        entity.setOldUnitCost(nvl(entity.getOldUnitCost()));
        entity.setNewUnitCost(nvl(entity.getNewUnitCost()));
        entity.setAmount(nvl(entity.getAmount()));
        entity.setReserveAmount(nvl(entity.getReserveAmount()));
    }
    private ValuationMethod method(StockValuationDocument document) {
        return document.getValuationMethod();
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<StockValuationDocumentResponse> search(StockValuationDocumentSearchRequest request, Pageable pageable) {
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
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public StockValuationDocumentResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public StockValuationDocumentResponse create(StockValuationDocumentCreateRequest dto) {
        StockValuationDocument entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        StockValuationDocument saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public StockValuationDocumentResponse update(UUID id, StockValuationDocumentUpdateRequest dto) {
        StockValuationDocument entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireGenericMutationAllowed(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        StockValuationDocument saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        StockValuationDocument entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireGenericMutationAllowed(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public StockValuationDocumentResponse restore(UUID id) {
        StockValuationDocument entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireGenericMutationAllowed(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        StockValuationDocument saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    private void requireGenericMutationAllowed(StockValuationDocument entity) {
        if ("POSTED".equals(entity.getStatus())) {
            throw new BusinessException("POSTED valuation document is immutable; create a reversal or correction");
        }
        if ("APPROVED".equals(entity.getStatus())) {
            throw new BusinessException("APPROVED valuation document is immutable; post or create a correction");
        }
        if (!"DRAFT".equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT valuation document can be edited");
        }
    }
    protected StockValuationDocument findEntity(UUID id) {
        StockValuationDocument entity = repository.findByIdAndIsDeletedFalse(id)
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

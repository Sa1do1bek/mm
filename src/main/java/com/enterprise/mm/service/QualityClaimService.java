package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.BusinessActionDtos.SupplierEvaluationRequest;
import com.enterprise.mm.payload.QualityClaimResponse;
import com.enterprise.mm.payload.QualityClaimUpdateRequest;
import com.enterprise.mm.payload.QualityClaimCreateRequest;
import com.enterprise.mm.payload.QualityControlDtos.QualityActionRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierCorrectiveActionCreateRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionResponse;
import com.enterprise.mm.payload.QualityClaimSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.QualityClaimMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.QualityClaimStatus;
import com.enterprise.mm.entity.QualityClaim;
import com.enterprise.mm.entity.QualityNonconformance;
import com.enterprise.mm.repository.QualityClaimRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class QualityClaimService {
    private final String entityName = "QualityClaim";
    private final QualityClaimRepository repository;
    private final RoleDataScopeService roleDataScopeService;
    private final QualityClaimMapper mapper;
    private final QualityControlGuard guard;
    private final QualityNonconformanceService nonconformanceService;
    private final SupplierCorrectiveActionService correctiveActionService;
    private final SupplierEvaluationService supplierEvaluationService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;

    protected void beforeCreate(QualityClaim entity, QualityClaimCreateRequest dto) {
        assertClaimScope(entity);
        normalize(entity);
        hydrateFromNonconformance(entity);
        validateLinks(entity);
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

        if (entity.getClaimNumber() == null || entity.getClaimNumber().isBlank()) {
            entity.setClaimNumber(entity.getCode());
        }
        ensureUnique(entity);
        entity.setStatus(QualityClaimStatus.DRAFT.name());
    }
    protected void beforeUpdate(QualityClaim entity, QualityClaimUpdateRequest dto) {
        assertClaimScope(entity);
        if (!QualityClaimStatus.DRAFT.name().equals(entity.getStatus()) && !QualityClaimStatus.ACKNOWLEDGED.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT or ACKNOWLEDGED quality claim can be edited");
        }
        normalize(entity);
        hydrateFromNonconformance(entity);
        validateLinks(entity);
        ensureUnique(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    @Transactional
    public QualityClaimResponse send(UUID id) {
        QualityClaim claim = findEntity(id);
        if (!QualityClaimStatus.DRAFT.name().equals(claim.getStatus())) {
            throw new BusinessException("Only DRAFT quality claim can be sent");
        }
        claim.setStatus(QualityClaimStatus.SENT.name());
        claim.setSentAt(LocalDateTime.now());
        QualityClaim saved = repository.save(claim);
        if (saved.getQualityNonconformanceId() != null) {
            nonconformanceService.attachClaim(saved.getQualityNonconformanceId(), saved.getId());
        }
        supplierEvaluationService.evaluate(new SupplierEvaluationRequest(
            saved.getSupplierId(),
            saved.getPurchaseOrderId(),
            BigDecimal.valueOf(90),
            BigDecimal.valueOf(55),
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(90),
            BigDecimal.valueOf(35)
        ));
        integrationEventService.emit("SUPPLIER.QUALITY_CLAIM_SENT", "SUPPLIER_PORTAL", saved.getId(), Map.of(
            "qualityClaimId", saved.getId(),
            "claimNumber", saved.getClaimNumber() == null ? "" : saved.getClaimNumber(),
            "claimName", saved.getName() == null ? "" : saved.getName(),
            "supplierId", saved.getSupplierId(),
            "purchaseOrderId", saved.getPurchaseOrderId() == null ? "" : saved.getPurchaseOrderId(),
            "purchaseOrderCode", saved.getPurchaseOrder() != null ? saved.getPurchaseOrder().getCode() : "",
            "nonconformanceId", saved.getQualityNonconformanceId() == null ? "" : saved.getQualityNonconformanceId(),
            "claimAmount", saved.getClaimAmount() == null ? BigDecimal.ZERO : saved.getClaimAmount(),
            "status", saved.getStatus(),
            "sentAt", saved.getSentAt() != null ? saved.getSentAt().toString() : LocalDateTime.now().toString()
        ));
        auditService.log("QualityClaim", id, "SEND", saved.getClaimNumber(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public QualityClaimResponse acknowledge(UUID id, QualityActionRequest request) {
        QualityClaim claim = findEntity(id);
        if (!QualityClaimStatus.SENT.name().equals(claim.getStatus())) {
            throw new BusinessException("Only SENT quality claim can be acknowledged");
        }
        claim.setStatus(QualityClaimStatus.ACKNOWLEDGED.name());
        claim.setAcknowledgedAt(LocalDateTime.now());
        claim.setSupplierResponse(request == null ? claim.getSupplierResponse() : request.comment());
        auditService.log("QualityClaim", id, "ACKNOWLEDGE", claim.getClaimNumber(), null, null);
        return mapper.toResponse(repository.save(claim));
    }
    @Transactional
    public QualityClaimResponse requireCapa(UUID id, QualityActionRequest request) {
        QualityClaim claim = findEntity(id);
        SupplierCorrectiveActionCreateRequest capaRequest = new SupplierCorrectiveActionCreateRequest();
        capaRequest.setSupplierId(claim.getSupplierId());
        capaRequest.setName(BusinessNameGenerator.workflowDocumentName("SupplierCorrectiveAction", claim.getName()));
        capaRequest.setSourceType("QUALITY_CLAIM");
        capaRequest.setSourceId(claim.getId());
        capaRequest.setIssueTitle(claim.getName());
        capaRequest.setIssueDescription(claim.getDescription());
        capaRequest.setSeverity("HIGH");
        capaRequest.setDueDate(LocalDate.now().plusDays(7));
        SupplierCorrectiveActionResponse capa = correctiveActionService.create(capaRequest);
        capa = correctiveActionService.open(capa.getId());
        claim.setCorrectiveActionId(capa.getId());
        claim.setStatus(QualityClaimStatus.CAPA_REQUIRED.name());
        auditService.log("QualityClaim", id, "REQUIRE_CAPA", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(claim));
    }
    @Transactional
    public QualityClaimResponse accept(UUID id, QualityActionRequest request) {
        QualityClaim claim = findEntity(id);
        claim.setStatus(QualityClaimStatus.ACCEPTED.name());
        claim.setSupplierResponse(request == null ? claim.getSupplierResponse() : request.comment());
        auditService.log("QualityClaim", id, "ACCEPT", claim.getClaimNumber(), null, null);
        return mapper.toResponse(repository.save(claim));
    }
    @Transactional
    public QualityClaimResponse reject(UUID id, QualityActionRequest request) {
        QualityClaim claim = findEntity(id);
        claim.setStatus(QualityClaimStatus.REJECTED.name());
        claim.setSupplierResponse(request == null ? claim.getSupplierResponse() : request.comment());
        auditService.log("QualityClaim", id, "REJECT", claim.getClaimNumber(), null, null);
        return mapper.toResponse(repository.save(claim));
    }
    @Transactional
    public QualityClaimResponse close(UUID id, QualityActionRequest request) {
        QualityClaim claim = findEntity(id);
        if (!QualityClaimStatus.ACCEPTED.name().equals(claim.getStatus()) && !QualityClaimStatus.REJECTED.name().equals(claim.getStatus()) && !QualityClaimStatus.CAPA_REQUIRED.name().equals(claim.getStatus())) {
            throw new BusinessException("Only ACCEPTED, REJECTED or CAPA_REQUIRED quality claim can be closed");
        }
        claim.setStatus(QualityClaimStatus.CLOSED.name());
        claim.setClosedAt(LocalDateTime.now());
        claim.setSupplierResponse(request == null ? claim.getSupplierResponse() : request.comment());
        auditService.log("QualityClaim", id, "CLOSE", claim.getClaimNumber(), null, null);
        return mapper.toResponse(repository.save(claim));
    }
    @Transactional
    public QualityClaimResponse cancel(UUID id, QualityActionRequest request) {
        QualityClaim claim = findEntity(id);
        if (QualityClaimStatus.CLOSED.name().equals(claim.getStatus())) {
            throw new BusinessException("Closed quality claim cannot be cancelled");
        }
        claim.setStatus(QualityClaimStatus.CANCELLED.name());
        auditService.log("QualityClaim", id, "CANCEL", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(claim));
    }
    private void hydrateFromNonconformance(QualityClaim entity) {
        if (entity.getQualityNonconformanceId() == null) {
            return;
        }
        QualityNonconformance nc = guard.nonconformance(entity.getQualityNonconformanceId());
        entity.setQualityInspectionId(nc.getQualityInspectionId());
        entity.setSupplierId(nc.getSupplierId());
        entity.setPurchaseOrderId(nc.getPurchaseOrderId());
        entity.setMaterialId(nc.getMaterialId());
    }
    private void validateLinks(QualityClaim entity) {
        guard.supplier(entity.getSupplierId());
        guard.purchaseOrder(entity.getPurchaseOrderId());
        if (entity.getMaterialId() != null) {
            guard.material(entity.getMaterialId());
        }
        if (entity.getQualityInspectionId() != null) {
            guard.inspection(entity.getQualityInspectionId());
        }
    }
    private void normalize(QualityClaim entity) {
        if (entity.getClaimNumber() != null) {
            entity.setClaimNumber(entity.getClaimNumber().trim());
        }
        if (entity.getClaimDate() == null) {
            entity.setClaimDate(LocalDate.now());
        }
        entity.setClaimAmount(entity.getClaimAmount() == null ? BigDecimal.ZERO : entity.getClaimAmount());
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode("UZS");
        } else {
            entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }
    }
    private void ensureUnique(QualityClaim entity) {
        if (entity.getClaimNumber() == null || entity.getClaimNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndClaimNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getClaimNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Quality claim number already exists");
            });
    }
    @Transactional(readOnly = true)
    public Page<QualityClaimResponse> search(QualityClaimSearchRequest request, Pageable pageable) {
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
            filter.supplierId(),
            filter.purchaseOrderId(),
            filter.materialIds(),
            !filter.hasMaterialIds(),
            roleDataScopeService.effectiveWarehouseId(null),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public QualityClaimResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public QualityClaimResponse create(QualityClaimCreateRequest dto) {
        QualityClaim entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        QualityClaim saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public QualityClaimResponse update(UUID id, QualityClaimUpdateRequest dto) {
        QualityClaim entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        QualityClaim saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        QualityClaim entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public QualityClaimResponse restore(UUID id) {
        QualityClaim entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        assertClaimScope(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        QualityClaim saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected QualityClaim findEntity(UUID id) {
        QualityClaim entity = repository.findByIdAndIsDeletedFalse(id)
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
        assertClaimScope(entity);
        return entity;
    }
    private void assertClaimScope(QualityClaim entity) {
        if (roleDataScopeService.effectiveWarehouseId(null) == null) return;
        if (entity.getQualityInspectionId() != null) {
            guard.inspection(entity.getQualityInspectionId());
        } else if (entity.getQualityNonconformanceId() != null) {
            guard.nonconformance(entity.getQualityNonconformanceId());
        } else {
            roleDataScopeService.assertWarehouseAccess(null);
        }
    }

    protected boolean companyScoped() {
        return true;
    }
}


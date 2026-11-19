package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.QualityControlDtos.QualityActionRequest;
import com.enterprise.mm.payload.QualityNonconformanceResponse;
import com.enterprise.mm.payload.QualityNonconformanceUpdateRequest;
import com.enterprise.mm.payload.QualityNonconformanceCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierCorrectiveActionCreateRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionResponse;
import com.enterprise.mm.payload.QualityNonconformanceSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.QualityNonconformanceMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.QualityDisposition;
import com.enterprise.mm.enums.QualityNonconformanceStatus;
import com.enterprise.mm.enums.QualitySeverity;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.QualityNonconformance;
import com.enterprise.mm.repository.QualityNonconformanceRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QualityNonconformanceService {
    private final String entityName = "QualityNonconformance";
    private final QualityNonconformanceRepository repository;
    private final RoleDataScopeService roleDataScopeService;
    private final QualityNonconformanceMapper mapper;
    private final QualityControlGuard guard;
    private final SupplierCorrectiveActionService correctiveActionService;
    private final QualityQuarantineLotService quarantineLotService;
    private final AuditService auditService;
    protected void beforeCreate(QualityNonconformance entity, QualityNonconformanceCreateRequest dto) {
        normalize(entity);
        hydrateFromInspection(entity);
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

        entity.setStatus(QualityNonconformanceStatus.OPEN.name());
        if (entity.getDetectedAt() == null) {
            entity.setDetectedAt(LocalDateTime.now());
        }
    }
    @Transactional
    public QualityNonconformanceResponse create(QualityNonconformanceCreateRequest dto) {
        QualityNonconformance entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        QualityNonconformance savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "CREATE", savedEntity.getCode(), null, null);
        QualityNonconformanceResponse saved = mapper.toResponse(savedEntity);
        quarantineLotService.linkNonconformance(saved.getQualityInspectionId(), saved.getId());
        return saved;
    }
    protected void beforeUpdate(QualityNonconformance entity, QualityNonconformanceUpdateRequest dto) {
        if (QualityNonconformanceStatus.CLOSED.name().equals(entity.getStatus()) || QualityNonconformanceStatus.CANCELLED.name().equals(entity.getStatus())) {
            throw new BusinessException("Closed or cancelled nonconformance cannot be edited");
        }
        normalize(entity);
        hydrateFromInspection(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    @Transactional
    public QualityNonconformanceResponse review(UUID id) {
        QualityNonconformance nc = findEntity(id);
        if (!QualityNonconformanceStatus.OPEN.name().equals(nc.getStatus())) {
            throw new BusinessException("Only OPEN nonconformance can be reviewed");
        }
        nc.setStatus(QualityNonconformanceStatus.UNDER_REVIEW.name());
        auditService.log("QualityNonconformance", id, "REVIEW", nc.getSeverity(), null, null);
        return mapper.toResponse(repository.save(nc));
    }
    @Transactional
    public QualityNonconformanceResponse requireCapa(UUID id, QualityActionRequest request) {
        QualityNonconformance nc = findEntity(id);
        if (nc.getSupplierId() == null) {
            throw new BusinessException("Supplier is required to create CAPA");
        }
        SupplierCorrectiveActionCreateRequest capaRequest = new SupplierCorrectiveActionCreateRequest();
        capaRequest.setSupplierId(nc.getSupplierId());
        capaRequest.setName(BusinessNameGenerator.workflowDocumentName("SupplierCorrectiveAction", nc.getName()));
        capaRequest.setSourceType("QUALITY_NONCONFORMANCE");
        capaRequest.setSourceId(nc.getId());
        capaRequest.setIssueTitle(nc.getName());
        capaRequest.setIssueDescription(nc.getDefectDescription());
        capaRequest.setSeverity(nc.getSeverity());
        capaRequest.setDueDate(LocalDate.now().plusDays(QualitySeverity.CRITICAL.name().equals(nc.getSeverity()) ? 3 : 10));
        SupplierCorrectiveActionResponse capa = correctiveActionService.create(capaRequest);
        capa = correctiveActionService.open(capa.getId());
        nc.setCapaRequired(true);
        nc.setCorrectiveActionId(capa.getId());
        nc.setStatus(QualityNonconformanceStatus.CAPA_REQUIRED.name());
        auditService.log("QualityNonconformance", id, "REQUIRE_CAPA", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(nc));
    }
    @Transactional
    public QualityNonconformanceResponse acceptDeviation(UUID id, QualityActionRequest request) {
        QualityNonconformance nc = findEntity(id);
        nc.setStatus(QualityNonconformanceStatus.ACCEPTED_WITH_DEVIATION.name());
        nc.setDisposition(QualityDisposition.ACCEPT_WITH_DEVIATION.name());
        auditService.log("QualityNonconformance", id, "ACCEPT_WITH_DEVIATION", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(nc));
    }
    @Transactional
    public QualityNonconformanceResponse reject(UUID id, QualityActionRequest request) {
        QualityNonconformance nc = findEntity(id);
        nc.setStatus(QualityNonconformanceStatus.REJECTED.name());
        nc.setDisposition(QualityDisposition.RETURN_TO_SUPPLIER.name());
        auditService.log("QualityNonconformance", id, "REJECT", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(nc));
    }
    @Transactional
    public QualityNonconformanceResponse close(UUID id, QualityActionRequest request) {
        QualityNonconformance nc = findEntity(id);
        if (nc.isCapaRequired() && nc.getCorrectiveActionId() == null) {
            throw new BusinessException("CAPA is required before closing nonconformance");
        }
        nc.setStatus(QualityNonconformanceStatus.CLOSED.name());
        nc.setClosedAt(LocalDateTime.now());
        auditService.log("QualityNonconformance", id, "CLOSE", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(nc));
    }
    @Transactional
    public QualityNonconformanceResponse cancel(UUID id, QualityActionRequest request) {
        QualityNonconformance nc = findEntity(id);
        if (QualityNonconformanceStatus.CLOSED.name().equals(nc.getStatus())) {
            throw new BusinessException("Closed nonconformance cannot be cancelled");
        }
        nc.setStatus(QualityNonconformanceStatus.CANCELLED.name());
        auditService.log("QualityNonconformance", id, "CANCEL", request == null ? null : request.comment(), null, null);
        return mapper.toResponse(repository.save(nc));
    }
    @Transactional
    public void attachClaim(UUID id, UUID claimId) {
        QualityNonconformance nc = findEntity(id);
        nc.setClaimId(claimId);
        repository.save(nc);
    }
    private void hydrateFromInspection(QualityNonconformance entity) {
        QualityInspection inspection = guard.inspection(entity.getQualityInspectionId());
        entity.setMaterialId(inspection.getMaterialId());
        entity.setSupplierId(inspection.getSupplierId());
        entity.setPurchaseOrderId(inspection.getPurchaseOrderId());
        entity.setStockMovementId(inspection.getStockMovementId());
    }
    private void normalize(QualityNonconformance entity) {
        entity.setSeverity(normalizeCode(entity.getSeverity(), QualitySeverity.MEDIUM.name(), enumNames(QualitySeverity.values()), "severity"));
        entity.setDisposition(normalizeCode(entity.getDisposition(), QualityDisposition.QUARANTINE.name(), enumNames(QualityDisposition.values()), "disposition"));
        if (entity.getDetectedQuantity() == null) {
            entity.setDetectedQuantity(java.math.BigDecimal.ZERO);
        }
        if (entity.getNonconformanceType() != null) {
            entity.setNonconformanceType(entity.getNonconformanceType().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        }
        if (QualitySeverity.HIGH.name().equals(entity.getSeverity()) || QualitySeverity.CRITICAL.name().equals(entity.getSeverity())) {
            entity.setCapaRequired(true);
        }
    }
    private String normalizeCode(String value, String fallback, String[] allowed, String field) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (String item : allowed) {
            if (item.equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException("Unsupported " + field + ": " + value);
    }
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    @Transactional(readOnly = true)
    public Page<QualityNonconformanceResponse> search(QualityNonconformanceSearchRequest request, Pageable pageable) {
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
    public QualityNonconformanceResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public QualityNonconformanceResponse update(UUID id, QualityNonconformanceUpdateRequest dto) {
        QualityNonconformance entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        QualityNonconformance saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        QualityNonconformance entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public QualityNonconformanceResponse restore(UUID id) {
        QualityNonconformance entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        guard.inspection(entity.getQualityInspectionId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        QualityNonconformance saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected QualityNonconformance findEntity(UUID id) {
        QualityNonconformance entity = repository.findByIdAndIsDeletedFalse(id)
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
        guard.inspection(entity.getQualityInspectionId());
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }
}


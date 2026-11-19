package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierKycDecisionRequest;
import com.enterprise.mm.payload.SupplierKycCheckResponse;
import com.enterprise.mm.payload.SupplierKycCheckUpdateRequest;
import com.enterprise.mm.payload.SupplierKycCheckCreateRequest;
import com.enterprise.mm.payload.SupplierKycCheckSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierKycCheckMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.SupplierKycCheckType;
import com.enterprise.mm.enums.SupplierKycRiskLevel;
import com.enterprise.mm.enums.SupplierKycStatus;
import com.enterprise.mm.entity.SupplierKycCheck;
import com.enterprise.mm.repository.SupplierKycCheckRepository;
import java.math.BigDecimal;
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
public class SupplierKycCheckService {
    private final String entityName = "SupplierKycCheck";
    private final SupplierKycCheckRepository repository;
    private final SupplierKycCheckMapper mapper;
    private final SupplierComplianceService complianceService;
    private final AuditService auditService;
    @Transactional
    public SupplierKycCheckResponse create(SupplierKycCheckCreateRequest dto) {
        SupplierKycCheck entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        SupplierKycCheck savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "CREATE", savedEntity.getCode(), null, null);
        SupplierKycCheckResponse saved = mapper.toResponse(savedEntity);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return saved;
    }
    @Transactional
    public SupplierKycCheckResponse update(UUID id, SupplierKycCheckUpdateRequest dto) {
        SupplierKycCheck entity = findMutationEntity(id);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        SupplierKycCheck savedEntity = repository.save(entity);
        auditService.log(entityName, savedEntity.getId(), "UPDATE", savedEntity.getCode(), null, null);
        SupplierKycCheckResponse saved = mapper.toResponse(savedEntity);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return saved;
    }
    @Transactional
    public SupplierKycCheckResponse request(UUID id) {
        SupplierKycCheck check = findMutationEntity(id);
        check.setStatus(SupplierKycStatus.REQUESTED.name());
        auditService.log("SupplierKycCheck", id, "REQUEST", check.getCheckType(), null, null);
        return saveAndApply(check);
    }
    @Transactional
    public SupplierKycCheckResponse pass(UUID id, SupplierKycDecisionRequest request) {
        SupplierKycCheck check = findMutationEntity(id);
        check.setStatus(SupplierKycStatus.PASSED.name());
        applyDecision(check, request);
        check.setRemediationRequired(false);
        auditService.log("SupplierKycCheck", id, "PASS", check.getCheckType(), null, null);
        return saveAndApply(check);
    }
    @Transactional
    public SupplierKycCheckResponse fail(UUID id, SupplierKycDecisionRequest request) {
        SupplierKycCheck check = findMutationEntity(id);
        check.setStatus(SupplierKycStatus.FAILED.name());
        applyDecision(check, request);
        if (request == null || request.remediationRequired() == null) {
            check.setRemediationRequired(true);
        }
        auditService.log("SupplierKycCheck", id, "FAIL", check.getRiskLevel(), null, null);
        return saveAndApply(check);
    }
    @Transactional
    public SupplierKycCheckResponse waive(UUID id, SupplierKycDecisionRequest request) {
        SupplierKycCheck check = findMutationEntity(id);
        check.setStatus(SupplierKycStatus.WAIVED.name());
        applyDecision(check, request);
        check.setRemediationRequired(false);
        auditService.log("SupplierKycCheck", id, "WAIVE", check.getFindings(), null, null);
        return saveAndApply(check);
    }
    @Transactional
    public SupplierKycCheckResponse expire(UUID id) {
        SupplierKycCheck check = findMutationEntity(id);
        check.setStatus(SupplierKycStatus.EXPIRED.name());
        auditService.log("SupplierKycCheck", id, "EXPIRE", check.getCheckType(), null, null);
        return saveAndApply(check);
    }
    protected void beforeCreate(SupplierKycCheck entity, SupplierKycCheckCreateRequest dto) {
        var supplier = complianceService.validateSupplier(entity.getSupplierId());
        if (!Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())) {
            throw new BusinessException("Supplier must belong to the current company");
        }
        entity.setStatus(SupplierKycStatus.DRAFT.name());
        entity.setCheckedAt(null);
        entity.setRemediationRequired(false);
        normalize(entity);
        if (entity.getNextReviewDate() == null) {
            entity.setNextReviewDate(LocalDate.now().plusMonths("CRITICAL".equals(entity.getRiskLevel()) ? 3 : 12));
        }
        entity.setCompanyId(CompanyContext.currentCompany());
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected void beforeUpdate(SupplierKycCheck entity, SupplierKycCheckUpdateRequest dto) {
        complianceService.validateSupplier(entity.getSupplierId());
        normalize(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    private SupplierKycCheckResponse saveAndApply(SupplierKycCheck check) {
        if (check.getCheckedAt() == null && isCompletedStatus(check.getStatus())) {
            check.setCheckedAt(LocalDateTime.now());
        }
        SupplierKycCheck saved = repository.save(check);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return mapper.toResponse(saved);
    }
    private void applyDecision(SupplierKycCheck check, SupplierKycDecisionRequest request) {
        if (request == null) {
            return;
        }
        if (request.riskLevel() != null && !request.riskLevel().isBlank()) {
            check.setRiskLevel(normalizeRisk(request.riskLevel()));
        }
        if (request.findings() != null) {
            check.setFindings(request.findings());
        }
        if (request.remediationRequired() != null) {
            check.setRemediationRequired(request.remediationRequired());
        }
        check.setCheckedAt(LocalDateTime.now());
    }
    private void normalize(SupplierKycCheck entity) {
        entity.setCheckType(complianceService.normalizeCode(entity.getCheckType(), SupplierKycCheckType.TAX_REGISTRATION.name(), "checkType", enumNames(SupplierKycCheckType.values())));
        entity.setRiskLevel(normalizeRisk(entity.getRiskLevel()));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(SupplierKycStatus.DRAFT.name());
        } else {
            entity.setStatus(complianceService.normalizeCode(entity.getStatus(), SupplierKycStatus.DRAFT.name(), "status", enumNames(SupplierKycStatus.values())));
        }
        if (entity.getValidUntil() != null && entity.getNextReviewDate() != null && entity.getNextReviewDate().isAfter(entity.getValidUntil())) {
            throw new BusinessException("nextReviewDate must be before validUntil");
        }
    }
    private String normalizeRisk(String value) {
        return complianceService.normalizeCode(value, SupplierKycRiskLevel.LOW.name(), "riskLevel", enumNames(SupplierKycRiskLevel.values()));
    }
    private boolean isCompletedStatus(String status) {
        return SupplierKycStatus.PASSED.name().equals(status)
            || SupplierKycStatus.FAILED.name().equals(status)
            || SupplierKycStatus.WAIVED.name().equals(status);
    }
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    @Transactional(readOnly = true)
    public Page<SupplierKycCheckResponse> search(SupplierKycCheckSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            searchPattern(filter),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            filter.supplierId(),
            normalizeFilter(request.checkType()),
            normalizeFilter(request.riskLevel()),
            request.remediationRequired(),
            request.scoreMin(),
            request.scoreMax(),
            likePattern(request.provider()),
            likePattern(request.externalCheckId()),
            request.checkedFrom() == null ? null : request.checkedFrom().atStartOfDay(),
            request.checkedTo() == null ? null : request.checkedTo().atTime(java.time.LocalTime.MAX),
            request.validUntilFrom(),
            request.validUntilTo(),
            request.nextReviewDateFrom(),
            request.nextReviewDateTo(),
            request.evidenceAttachmentId(),
            likePattern(request.findings()),
            pageable
        ).map(mapper::toResponse);
    }


    @Transactional(readOnly = true)
    public SupplierKycCheckResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public void softDelete(UUID id) {
        SupplierKycCheck entity = findMutationEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
        complianceService.applySupplierRisk(entity.getSupplierId());
    }
    @Transactional
    public SupplierKycCheckResponse restore(UUID id) {
        SupplierKycCheck entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireCurrentCompany(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierKycCheck saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        complianceService.applySupplierRisk(saved.getSupplierId());
        return mapper.toResponse(saved);
    }
    protected SupplierKycCheck findEntity(UUID id) {
        SupplierKycCheck entity = repository.findByIdAndIsDeletedFalse(id)
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

    private SupplierKycCheck findMutationEntity(UUID id) {
        SupplierKycCheck entity = findEntity(id);
        requireCurrentCompany(entity);
        return entity;
    }

    private void requireCurrentCompany(SupplierKycCheck entity) {
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String likePattern(String value) {
        String normalized = normalizeFilter(value);
        return normalized == null ? null : "%" + normalized + "%";
    }
}

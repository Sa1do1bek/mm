package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SegregationOfDutyViolationResponse;
import com.enterprise.mm.payload.SegregationOfDutyViolationUpdateRequest;
import com.enterprise.mm.payload.SegregationOfDutyViolationCreateRequest;
import com.enterprise.mm.payload.SegregationOfDutyViolationSearchRequest;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SegregationOfDutyViolationMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SegregationOfDutyRule;
import com.enterprise.mm.entity.SegregationOfDutyViolation;
import com.enterprise.mm.repository.SegregationOfDutyViolationRepository;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SegregationOfDutyViolationService {
    private final SegregationOfDutyViolationMapper mapper;
    private final String entityName = "SegregationOfDutyViolation";
    private final AuditService auditService;
    private final SegregationOfDutyViolationRepository repository;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
        SegregationOfDutyRule rule,
        UUID entityId,
        String entityType,
        String actionCode,
        String username,
        String matchedPrincipal,
        String decision,
        String message
    ) {
        SegregationOfDutyViolation violation = new SegregationOfDutyViolation();
        violation.setCode("SODV-" + UUID.randomUUID().toString().substring(0, 8));
        violation.setName(entityType + " " + actionCode + " " + decision);
        violation.setStatus(decision);
        violation.setRuleId(rule.getId());
        violation.setEntityType(entityType);
        violation.setEntityId(entityId);
        violation.setActionCode(actionCode);
        violation.setRuleType(rule.getRuleType());
        violation.setUsername(username);
        violation.setMatchedPrincipal(matchedPrincipal);
        violation.setDecision(decision);
        violation.setSeverity(rule.getSeverity());
        violation.setOccurredAt(LocalDateTime.now());
        violation.setMessage(message);
        repository.save(violation);
    }
    @Transactional(readOnly = true)
    public Page<SegregationOfDutyViolationResponse> search(SegregationOfDutyViolationSearchRequest request, Pageable pageable) {
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
    public SegregationOfDutyViolationResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public SegregationOfDutyViolationResponse create(SegregationOfDutyViolationCreateRequest dto) {
        SegregationOfDutyViolation entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        SegregationOfDutyViolation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public SegregationOfDutyViolationResponse update(UUID id, SegregationOfDutyViolationUpdateRequest dto) {
        SegregationOfDutyViolation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        SegregationOfDutyViolation saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        SegregationOfDutyViolation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public SegregationOfDutyViolationResponse restore(UUID id) {
        SegregationOfDutyViolation entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SegregationOfDutyViolation saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected SegregationOfDutyViolation findEntity(UUID id) {
        SegregationOfDutyViolation entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(SegregationOfDutyViolation entity, SegregationOfDutyViolationCreateRequest dto) {
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
    }
    protected void beforeUpdate(SegregationOfDutyViolation entity, SegregationOfDutyViolationUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }
}


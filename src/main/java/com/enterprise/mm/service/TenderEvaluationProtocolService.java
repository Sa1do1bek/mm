package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.TenderEvaluationProtocolResponse;
import com.enterprise.mm.payload.TenderEvaluationProtocolUpdateRequest;
import com.enterprise.mm.payload.TenderEvaluationProtocolCreateRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.TenderEvaluationProtocolMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TenderEvaluationProtocol;
import com.enterprise.mm.repository.TenderEvaluationProtocolRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.TenderEvaluationProtocolSearchRequest;

@Service
@RequiredArgsConstructor
public class TenderEvaluationProtocolService {
    private final TenderEvaluationProtocolRepository repository;
    private final TenderEvaluationProtocolMapper mapper;
    private final String entityName = "TenderEvaluationProtocol";
    private static final Set<String> WORKFLOW_MANAGED_STATUSES = Set.of("APPROVED", "POSTED");
    private final AuditService auditService;
    @Transactional(readOnly = true)
    public Page<TenderEvaluationProtocolResponse> search(TenderEvaluationProtocolSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            TenderEvaluationProtocol.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public TenderEvaluationProtocolResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public TenderEvaluationProtocolResponse create(TenderEvaluationProtocolCreateRequest dto) {
        TenderEvaluationProtocol entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        TenderEvaluationProtocol saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public TenderEvaluationProtocolResponse update(UUID id, TenderEvaluationProtocolUpdateRequest dto) {
        TenderEvaluationProtocol entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        rejectWorkflowManagedStatus(entity.getStatus(), "Workflow-approved tender evaluation protocols cannot be edited through the generic registry");
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        TenderEvaluationProtocol saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        TenderEvaluationProtocol entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public TenderEvaluationProtocolResponse restore(UUID id) {
        TenderEvaluationProtocol entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        TenderEvaluationProtocol saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected TenderEvaluationProtocol findEntity(UUID id) {
        TenderEvaluationProtocol entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(TenderEvaluationProtocol entity, TenderEvaluationProtocolCreateRequest dto) {
        rejectWorkflowManagedStatus(entity.getStatus(), "Use the RFQ evaluation action to create an approved tender evaluation protocol");
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
    protected void beforeUpdate(TenderEvaluationProtocol entity, TenderEvaluationProtocolUpdateRequest dto) {
        rejectWorkflowManagedStatus(entity.getStatus(), "Use the RFQ evaluation action to approve a tender evaluation protocol");
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private void rejectWorkflowManagedStatus(String status, String message) {
        if (status == null || status.isBlank()) {
            return;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (WORKFLOW_MANAGED_STATUSES.contains(normalized)) {
            throw new BusinessException(message, "tender.evaluation.workflow_action_required");
        }
    }
}

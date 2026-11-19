package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialQualityIssue;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialQualityIssueMapper;
import com.enterprise.mm.payload.MaterialQualityIssueCreateRequest;
import com.enterprise.mm.payload.MaterialQualityIssueResponse;
import com.enterprise.mm.payload.MaterialQualityIssueUpdateRequest;
import com.enterprise.mm.payload.MaterialQualityIssueSearchRequest;
import com.enterprise.mm.payload.MaterialQualityIssueActionRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.repository.MaterialQualityIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MaterialQualityIssueService {
    private static final Set<String> ISSUE_TYPES = Set.of(
        "MISSING_REQUIRED_FIELD", "DUPLICATE_CODE", "DUPLICATE_NAME", "INVALID_UOM", "MISSING_IKPU",
        "MISSING_TNVED", "MISSING_BARCODE", "INVALID_VALUATION_METHOD", "MISSING_MIN_MAX_STOCK", "OBSOLETE_WITHOUT_REPLACEMENT"
    );
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final MaterialQualityIssueRepository repository;
    private final MaterialQualityIssueMapper mapper;
    private final MaterialCatalogGuard catalogGuard;
    private final AuditService auditService;
    private final String entityName = "MaterialQualityIssue";

    @Transactional(readOnly = true)
    public Page<MaterialQualityIssueResponse> search(MaterialQualityIssueSearchRequest request, Pageable pageable) {
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
            filter.materialIds(),
            !filter.hasMaterialIds(),
            pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public MaterialQualityIssueResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public MaterialQualityIssueResponse create(MaterialQualityIssueCreateRequest dto) {
        MaterialQualityIssue entity = mapper.toEntity(dto);
        beforeCreate(entity);
        entity.setStatus("OPEN");
        clearWorkflowEvidence(entity);
        MaterialQualityIssue saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MaterialQualityIssueResponse update(UUID id, MaterialQualityIssueUpdateRequest dto) {
        MaterialQualityIssue entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireOpen(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity);
        MaterialQualityIssue saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MaterialQualityIssueResponse assign(UUID id, MaterialQualityIssueActionRequest request) {
        MaterialQualityIssue entity = commandEntity(id, request, Set.of("OPEN"), "assign");
        String assignedTo = trim(request.assignedTo());
        if (assignedTo == null || assignedTo.isBlank()) {
            throw new BadRequestException("Assignee is required to assign a material quality issue");
        }
        entity.setAssignedTo(assignedTo);
        entity.setAssignedAt(LocalDateTime.now());
        return saveTransition(entity, "ASSIGNED", "ASSIGN", request.comment());
    }

    @Transactional
    public MaterialQualityIssueResponse start(UUID id, MaterialQualityIssueActionRequest request) {
        MaterialQualityIssue entity = commandEntity(id, request, Set.of("ASSIGNED"), "start");
        entity.setStartedAt(LocalDateTime.now());
        return saveTransition(entity, "IN_PROGRESS", "START", request.comment());
    }

    @Transactional
    public MaterialQualityIssueResponse resolve(UUID id, MaterialQualityIssueActionRequest request) {
        MaterialQualityIssue entity = commandEntity(id, request, Set.of("IN_PROGRESS"), "resolve");
        String evidence = trim(request.evidenceReference());
        if (evidence == null || evidence.isBlank()) {
            throw new BusinessException("Evidence reference is required to resolve a material quality issue");
        }
        entity.setEvidenceReference(evidence);
        entity.setResolutionComment(trim(request.comment()));
        entity.setResolvedAt(LocalDateTime.now());
        return saveTransition(entity, "RESOLVED", "RESOLVE", request.comment());
    }

    @Transactional
    public MaterialQualityIssueResponse ignore(UUID id, MaterialQualityIssueActionRequest request) {
        MaterialQualityIssue entity = commandEntity(
            id, request, Set.of("OPEN", "ASSIGNED", "IN_PROGRESS"), "ignore");
        entity.setResolutionComment(trim(request.comment()));
        entity.setEvidenceReference(trim(request.evidenceReference()));
        entity.setResolvedAt(LocalDateTime.now());
        return saveTransition(entity, "IGNORED", "IGNORE", request.comment());
    }

    @Transactional
    public void softDelete(UUID id) {
        MaterialQualityIssue entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public MaterialQualityIssueResponse restore(UUID id) {
        MaterialQualityIssue entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        beforeUpdate(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        MaterialQualityIssue saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected void beforeCreate(MaterialQualityIssue entity) {
        defaultBaseFields(entity);
        normalize(entity);
    }

    protected void beforeUpdate(MaterialQualityIssue entity) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        normalize(entity);
    }

    protected MaterialQualityIssue findEntity(UUID id) {
        MaterialQualityIssue entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void validateScope(MaterialQualityIssue entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (companyScoped() && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    protected boolean companyScoped() {
        return true;
    }

    private void normalize(MaterialQualityIssue entity) {
        catalogGuard.requireMaterial(entity.getMaterialId(), "Material");
        entity.setIssueType(upper(entity.getIssueType()));
        entity.setSeverity(upper(entity.getSeverity()));
        if (!ISSUE_TYPES.contains(entity.getIssueType())) {
            throw new BusinessException("Unsupported issue type: " + entity.getIssueType());
        }
        if (!SEVERITIES.contains(entity.getSeverity())) {
            throw new BusinessException("Unsupported severity: " + entity.getSeverity());
        }
    }

    private void defaultBaseFields(MaterialQualityIssue entity) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("OPEN");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(entity.getIssueType() == null ? "Material quality issue" : entity.getIssueType());
        }
        if (entity.getIssueType() == null || entity.getIssueType().isBlank()) {
            entity.setIssueType("MISSING_REQUIRED_FIELD");
        }
        if (entity.getSeverity() == null || entity.getSeverity().isBlank()) {
            entity.setSeverity("MEDIUM");
        }
    }

    private MaterialQualityIssue commandEntity(
        UUID id,
        MaterialQualityIssueActionRequest request,
        Set<String> allowedStatuses,
        String action
    ) {
        MaterialQualityIssue entity = findEntity(id);
        if (request == null || request.expectedVersion() == null) {
            throw new BadRequestException("expectedVersion is required for material quality issue commands");
        }
        if (!Objects.equals(entity.getVersion(), request.expectedVersion())) {
            throw new ConflictException(
                "Material quality issue version conflict: expected=" + request.expectedVersion()
                    + ", actual=" + entity.getVersion());
        }
        if (!allowedStatuses.contains(entity.getStatus())) {
            throw new BusinessException(
                "Material quality issue cannot " + action + " from " + entity.getStatus());
        }
        String comment = trim(request.comment());
        if (comment == null || comment.isBlank()) {
            throw new BadRequestException("Comment is required for material quality issue commands");
        }
        return entity;
    }

    private MaterialQualityIssueResponse saveTransition(
        MaterialQualityIssue entity,
        String targetStatus,
        String action,
        String comment
    ) {
        entity.setStatus(targetStatus);
        MaterialQualityIssue saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), action, trim(comment), null, null);
        return mapper.toResponse(saved);
    }

    private void requireOpen(MaterialQualityIssue entity) {
        if (!"OPEN".equals(entity.getStatus())) {
            throw new BusinessException("Only OPEN material quality issues are editable");
        }
    }

    private void clearWorkflowEvidence(MaterialQualityIssue entity) {
        entity.setAssignedTo(null);
        entity.setResolutionComment(null);
        entity.setEvidenceReference(null);
        entity.setAssignedAt(null);
        entity.setStartedAt(null);
        entity.setResolvedAt(null);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

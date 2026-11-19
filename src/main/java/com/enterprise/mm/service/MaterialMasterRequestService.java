package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialMasterRequest;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialMasterRequestMapper;
import com.enterprise.mm.payload.MaterialMasterRequestCreateRequest;
import com.enterprise.mm.payload.MaterialMasterRequestResponse;
import com.enterprise.mm.payload.MaterialMasterRequestUpdateRequest;
import com.enterprise.mm.payload.MaterialGovernanceActionRequest;
import com.enterprise.mm.payload.MaterialCreateRequest;
import com.enterprise.mm.payload.MaterialUpdateRequest;
import com.enterprise.mm.payload.MaterialResponse;
import com.enterprise.mm.payload.MaterialLifecycleRequest;
import com.enterprise.mm.payload.MaterialAttributeRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialMasterRequestRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.enterprise.mm.payload.MaterialMasterRequestSearchRequest;

@Service
@RequiredArgsConstructor
public class MaterialMasterRequestService {
    private static final Set<String> REQUEST_TYPES = Set.of("CREATE", "CHANGE", "MERGE", "OBSOLETE", "REACTIVATE");

    private final MaterialMasterRequestRepository repository;
    private final MaterialMasterRequestMapper mapper;
    private final AuditService auditService;
    private final MaterialCatalogGuard catalogGuard;
    private final MaterialGroupRepository materialGroupRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final MaterialService materialService;
    private final String entityName = "MaterialMasterRequest";
    @Transactional(readOnly = true)
    public Page<MaterialMasterRequestResponse> search(MaterialMasterRequestSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            MaterialMasterRequest.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public MaterialMasterRequestResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public MaterialMasterRequestResponse create(MaterialMasterRequestCreateRequest dto) {
        MaterialMasterRequest entity = mapper.toEntity(dto);
        beforeCreate(entity);
        entity.setStatus("DRAFT");
        clearWorkflowEvidence(entity);
        MaterialMasterRequest saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MaterialMasterRequestResponse update(UUID id, MaterialMasterRequestUpdateRequest dto) {
        MaterialMasterRequest entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireEditable(entity);
        boolean reviseRejected = "REJECTED".equals(entity.getStatus());
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity);
        if (reviseRejected) {
            entity.setStatus("DRAFT");
            clearWorkflowEvidence(entity);
        }
        MaterialMasterRequest saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    @Transactional
    public MaterialMasterRequestResponse submit(UUID id, MaterialGovernanceActionRequest request) {
        return transition(id, request, Set.of("DRAFT", "REJECTED"), "SUBMITTED", "SUBMIT", entity -> {
            entity.setSubmittedAt(LocalDateTime.now());
            entity.setReviewedAt(null);
            entity.setDecidedAt(null);
            entity.setAppliedAt(null);
        });
    }

    @Transactional
    public MaterialMasterRequestResponse review(UUID id, MaterialGovernanceActionRequest request) {
        return transition(id, request, Set.of("SUBMITTED"), "IN_REVIEW", "REVIEW",
            entity -> entity.setReviewedAt(LocalDateTime.now()));
    }

    @Transactional
    public MaterialMasterRequestResponse approve(UUID id, MaterialGovernanceActionRequest request) {
        return transition(id, request, Set.of("IN_REVIEW"), "APPROVED", "APPROVE",
            entity -> entity.setDecidedAt(LocalDateTime.now()));
    }

    @Transactional
    public MaterialMasterRequestResponse reject(UUID id, MaterialGovernanceActionRequest request) {
        return transition(id, request, Set.of("IN_REVIEW"), "REJECTED", "REJECT",
            entity -> entity.setDecidedAt(LocalDateTime.now()));
    }

    @Transactional
    public MaterialMasterRequestResponse apply(UUID id, MaterialGovernanceActionRequest request) {
        return transition(id, request, Set.of("APPROVED"), "APPLIED", "APPLY",
            entity -> {
                applyApprovedChange(entity, request.comment().trim());
                entity.setAppliedAt(LocalDateTime.now());
            });
    }

    @Transactional
    public MaterialMasterRequestResponse cancel(UUID id, MaterialGovernanceActionRequest request) {
        return transition(
            id, request,
            Set.of("DRAFT", "SUBMITTED", "IN_REVIEW", "APPROVED", "REJECTED"),
            "CANCELLED", "CANCEL", entity -> { }
        );
    }

    @Transactional
    public void softDelete(UUID id) {
        MaterialMasterRequest entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public MaterialMasterRequestResponse restore(UUID id) {
        MaterialMasterRequest entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        beforeUpdate(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        MaterialMasterRequest saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }

    protected void beforeCreate(MaterialMasterRequest entity) {
        defaultBaseFields(entity);
        normalize(entity);
    }

    protected void beforeUpdate(MaterialMasterRequest entity) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        normalize(entity);
    }

    protected MaterialMasterRequest findEntity(UUID id) {
        MaterialMasterRequest entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void validateScope(MaterialMasterRequest entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (companyScoped() && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    protected boolean companyScoped() {
        return true;
    }

    private void normalize(MaterialMasterRequest entity) {
        entity.setRequestType(upper(entity.getRequestType()));
        if (!REQUEST_TYPES.contains(entity.getRequestType())) {
            throw new BusinessException("Unsupported request type: " + entity.getRequestType());
        }
        if (entity.getMaterialId() != null) {
            catalogGuard.requireMaterial(entity.getMaterialId(), "Material");
        }
        if (entity.getTargetMaterialId() != null) {
            catalogGuard.requireUsableMaterial(entity.getTargetMaterialId(), "Target material");
        }
        if (entity.getMaterialGroupId() != null) {
            materialGroupRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                    TenantContext.currentTenant(), entity.getCompanyId(), entity.getMaterialGroupId())
                .orElseThrow(() -> new BusinessException("Material group not found: " + entity.getMaterialGroupId()));
        }
        if (entity.getUomId() != null) {
            UnitOfMeasure unit = unitOfMeasureRepository.findByIdAndIsDeletedFalse(entity.getUomId())
                .orElseThrow(() -> new BusinessException("Unit of measure not found: " + entity.getUomId()));
            if (!TenantContext.currentTenant().equals(unit.getTenantId()) || unit.isDeleted()) {
                throw new BusinessException("Unit of measure not found: " + entity.getUomId());
            }
        }
        if (!"CREATE".equals(entity.getRequestType()) && entity.getMaterialId() == null) {
            throw new BusinessException("Material is required for " + entity.getRequestType() + " request");
        }
        if ("MERGE".equals(entity.getRequestType())) {
            if (entity.getTargetMaterialId() == null) {
                throw new BusinessException("Target material is required for MERGE request");
            }
            if (entity.getTargetMaterialId().equals(entity.getMaterialId())) {
                throw new BusinessException("MERGE source and target materials must be different");
            }
        } else if (entity.getTargetMaterialId() != null) {
            throw new BusinessException("Target material is only allowed for MERGE request");
        }
        if (entity.getRequestedBy() == null || entity.getRequestedBy().isBlank()) {
            entity.setRequestedBy("system");
        }
    }

    private void defaultBaseFields(MaterialMasterRequest entity) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("DRAFT");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(entity.getProposedName() == null || entity.getProposedName().isBlank() ? "Material master request" : entity.getProposedName());
        }
    }

    private MaterialMasterRequestResponse transition(
        UUID id,
        MaterialGovernanceActionRequest request,
        Set<String> allowedStatuses,
        String targetStatus,
        String action,
        java.util.function.Consumer<MaterialMasterRequest> mutation
    ) {
        MaterialMasterRequest entity = findEntity(id);
        String comment = requireCommand(entity, request, allowedStatuses, action);
        mutation.accept(entity);
        entity.setStatus(targetStatus);
        entity.setLastActionComment(comment);
        MaterialMasterRequest saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), action, comment, null, null);
        return mapper.toResponse(saved);
    }

    private String requireCommand(
        MaterialMasterRequest entity,
        MaterialGovernanceActionRequest request,
        Set<String> allowedStatuses,
        String action
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BadRequestException("expectedVersion is required for material master request commands");
        }
        if (!Objects.equals(entity.getVersion(), request.expectedVersion())) {
            throw new ConflictException(
                "Material master request version conflict: expected=" + request.expectedVersion()
                    + ", actual=" + entity.getVersion());
        }
        if (!allowedStatuses.contains(entity.getStatus())) {
            throw new BusinessException(
                "Material master request cannot " + action.toLowerCase(Locale.ROOT)
                    + " from " + entity.getStatus());
        }
        String comment = request.comment() == null ? null : request.comment().trim();
        if (comment == null || comment.isBlank()) {
            throw new BadRequestException("Comment is required for material master request commands");
        }
        return comment;
    }

    private void requireEditable(MaterialMasterRequest entity) {
        if (!Set.of("DRAFT", "REJECTED").contains(entity.getStatus())) {
            throw new BusinessException("Only DRAFT or REJECTED material master requests are editable");
        }
    }

    private void clearWorkflowEvidence(MaterialMasterRequest entity) {
        entity.setLastActionComment(null);
        entity.setSubmittedAt(null);
        entity.setReviewedAt(null);
        entity.setDecidedAt(null);
        entity.setAppliedAt(null);
    }

    private void applyApprovedChange(MaterialMasterRequest request, String comment) {
        switch (request.getRequestType()) {
            case "CREATE" -> {
                MaterialCreateRequest create = new MaterialCreateRequest();
                create.setName(request.getProposedName());
                create.setDescription(request.getDescription());
                create.setMaterialGroupId(request.getMaterialGroupId());
                create.setUnitOfMeasureId(request.getUomId());
                create.setMxikCode(request.getMxikCode());
                create.setTnvedCode(request.getTnvedCode());
                MaterialResponse created = materialService.create(create);
                request.setMaterialId(created.getId());
            }
            case "CHANGE" -> materialService.update(
                request.getMaterialId(), buildChangeRequest(materialService.get(request.getMaterialId()), request));
            case "OBSOLETE" -> materialService.markObsolete(
                request.getMaterialId(), new MaterialLifecycleRequest(null, comment));
            case "REACTIVATE" -> materialService.reactivate(
                request.getMaterialId(), new MaterialLifecycleRequest(null, comment));
            case "MERGE" -> materialService.markObsolete(
                request.getMaterialId(),
                new MaterialLifecycleRequest(request.getTargetMaterialId(), comment));
            default -> throw new BusinessException("Unsupported request type: " + request.getRequestType());
        }
    }

    private MaterialUpdateRequest buildChangeRequest(
        MaterialResponse current,
        MaterialMasterRequest change
    ) {
        MaterialUpdateRequest update = new MaterialUpdateRequest();
        update.setCode(current.getCode());
        update.setName(nonBlank(change.getProposedName(), current.getName()));
        update.setStatus(current.getMaterialStatus());
        update.setDescription(current.getDescription());
        update.setMaterialGroupId(Objects.requireNonNullElse(change.getMaterialGroupId(), current.getMaterialGroupId()));
        update.setUnitOfMeasureId(Objects.requireNonNullElse(change.getUomId(), current.getUnitOfMeasureId()));
        update.setValuationMethod(current.getValuationMethod());
        update.setMinStock(current.getMinStock());
        update.setSafetyStock(current.getSafetyStock());
        update.setReorderPoint(current.getReorderPoint());
        update.setBarcode(current.getBarcode());
        update.setQrCode(current.getQrCode());
        update.setRfidTag(current.getRfidTag());
        update.setMxikId(current.getMxikId());
        update.setMxikCode(nonBlank(change.getMxikCode(), current.getMxikCode()));
        update.setTnvedCodeId(current.getTnvedCodeId());
        update.setTnvedCode(nonBlank(change.getTnvedCode(), current.getTnvedCode()));
        update.setArticle(current.getArticle());
        update.setManufacturerId(current.getManufacturerId());
        update.setManufacturer(current.getManufacturer());
        update.setCountryOfOrigin(current.getCountryOfOrigin());
        update.setShelfLifeDays(current.getShelfLifeDays());
        update.setHazardClass(current.getHazardClass());
        update.setMaxStock(current.getMaxStock());
        update.setAttributes(current.getAttributes() == null
            ? List.of()
            : current.getAttributes().stream()
                .map(attribute -> new MaterialAttributeRequest(attribute.key(), attribute.value()))
                .toList());
        return update;
    }

    private String nonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred.trim();
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}


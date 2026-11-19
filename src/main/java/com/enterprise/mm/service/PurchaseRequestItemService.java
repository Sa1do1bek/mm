package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.PurchaseRequestItemResponse;
import com.enterprise.mm.payload.PurchaseRequestItemUpdateRequest;
import com.enterprise.mm.payload.PurchaseRequestItemCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.PurchaseRequestItemMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.PurchaseRequestItemSearchRequest;

@Service
@RequiredArgsConstructor
public class PurchaseRequestItemService {
    private final PurchaseRequestItemRepository repository;
    private final PurchaseRequestItemMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final ProcurementLineValidationService procurementLineValidationService;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final RoleDataScopeService roleDataScopeService;
    private final String entityName = "PurchaseRequestItem";
    private final AuditService auditService;
    private final RfqAggregationService rfqAggregationService;
    private final MaterialCostObservationService materialCostObservationService;
    private final ProcurementLineageEnricher procurementLineageEnricher;
    private final com.enterprise.mm.repository.DemandPlanItemPurchaseRequestAllocationRepository demandAllocationRepository;
    private final com.enterprise.mm.repository.DemandPlanItemRepository demandItemRepository;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    private final ProcurementDocumentCategoryValidator documentCategoryValidator;
    @Transactional(readOnly = true)
    public Page<PurchaseRequestItemResponse> search(PurchaseRequestItemSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            PurchaseRequestItem.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(this::toResponse);
    }
    @Transactional(readOnly = true)
    public PurchaseRequestItemResponse get(UUID id) {
        return toResponse(findEntity(id));
    }
    @Transactional
    public PurchaseRequestItemResponse create(PurchaseRequestItemCreateRequest dto) {
        if (dto.getId() != null || dto.getVersion() != null) {
            throw new BusinessException("New lines must not carry an existing identity", "purchaseRequest.items.invalidIdentity");
        }
        PurchaseRequestItem entity = mapper.toEntity(dto);
        PurchaseRequest parent = requireMutableParent(entity.getPurchaseRequestId());
        if (parent.getDemandPlanId() != null) {
            throw new BusinessException("New demand must be added through the demand plan", "purchaseRequest.items.sourceProtected");
        }
        beforeCreate(entity, dto, parent);
        PurchaseRequestItem saved = repository.save(entity);
        recordCostObservation(saved, parent);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public PurchaseRequestItemResponse update(UUID id, PurchaseRequestItemUpdateRequest dto) {
        PurchaseRequestItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        rfqAggregationService.assertPurchaseRequestItemMutable(id);
        UUID purchaseRequestId = entity.getPurchaseRequestId();
        String status = entity.getStatus();
        PurchaseRequest parent = requireMutableParent(purchaseRequestId);
        if (dto.getVersion() != null) requireVersion(entity, dto.getVersion());
        if (!Objects.equals(purchaseRequestId, dto.getPurchaseRequestId())) {
            throw new BusinessException("Purchase request item cannot be moved to another purchase request");
        }
        assertSourceFieldsUnchanged(entity, dto);
        mapper.updateEntity(entity, dto);
        entity.setPurchaseRequestId(purchaseRequestId);
        entity.setStatus(status);
        beforeUpdate(entity, dto, parent);
        PurchaseRequestItem saved = repository.save(entity);
        recordCostObservation(saved, parent);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        PurchaseRequestItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        rfqAggregationService.assertPurchaseRequestItemMutable(id);
        requireMutableParent(entity.getPurchaseRequestId());
        if (hasDemandSource(entity)) {
            throw new BusinessException("Source-backed demand lines cannot be removed", "purchaseRequest.items.sourceProtected");
        }
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public PurchaseRequestItemResponse restore(UUID id) {
        PurchaseRequestItem entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutableParent(entity.getPurchaseRequestId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        PurchaseRequestItem saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected PurchaseRequestItem findEntity(UUID id) {
        PurchaseRequestItem entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(PurchaseRequestItem entity, PurchaseRequestItemCreateRequest dto, PurchaseRequest parent) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        procurementLineValidationService.validateLine(dto.getLineType(), entity.getMaterialId(), entity.getServiceDescription(), entity.getQuantity(), "purchaseRequestItem");
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        }
        materialTypeSnapshotService.applySnapshot(entity, parent.getRequestType());
        validateDocumentCategory(parent, entity);
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
    protected void beforeUpdate(PurchaseRequestItem entity, PurchaseRequestItemUpdateRequest dto, PurchaseRequest parent) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        procurementLineValidationService.validateLine(dto.getLineType(), entity.getMaterialId(), entity.getServiceDescription(), entity.getQuantity(), "purchaseRequestItem");
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        }
        materialTypeSnapshotService.applySnapshot(entity, parent.getRequestType());
        validateDocumentCategory(parent, entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private PurchaseRequest requireMutableParent(UUID id) {
        PurchaseRequest parent = id == null ? null : purchaseRequestRepository.findByIdAndIsDeletedFalse(id)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                || com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElse(null);
        if (parent == null) {
            throw new NotFoundException("PurchaseRequest not found: " + id);
        }
        if (!roleDataScopeService.canAccessDocument(
                parent.getCreatedBy(),
                parent.getRequestedBy(),
                parent.getDepartmentId()
            )) {
            throw new NotFoundException("PurchaseRequest not found: " + id);
        }
        if (!"DRAFT".equals(parent.getStatus()) && !"RETURNED".equals(parent.getStatus())) {
            throw new BusinessException("Purchase request items can be changed only while parent is DRAFT or RETURNED");
        }
        return parent;
    }

    private void recordCostObservation(PurchaseRequestItem item, PurchaseRequest parent) {
        if (item.getLineType() != com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            return;
        }
        materialCostObservationService.recordPurchaseRequest(
            item.getMaterialId(),
            item.getQuantity(),
            item.getEstimatedPrice(),
            parent.getCurrencyCode(),
            parent.getId(),
            item.getId(),
            parent.getCode(),
            parent.getCreatedAt()
        );
    }
    protected boolean companyScoped() {
        return true;
    }

    @Transactional
    public void synchronizeItems(UUID parentId, java.util.List<PurchaseRequestItemCreateRequest> incoming,
        java.util.List<com.enterprise.mm.payload.PurchaseRequestItemRemovalRequest> removals) {
        if (incoming == null && removals == null) return;
        PurchaseRequest parent = requireMutableParent(parentId);
        var existing = repository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), parentId)
            .stream().filter(item -> Objects.equals(parent.getCompanyId(), item.getCompanyId()))
            .collect(java.util.stream.Collectors.toMap(PurchaseRequestItem::getId, item -> item));
        var touched = new java.util.HashSet<UUID>();
        var updates = incoming == null ? java.util.List.<PurchaseRequestItemCreateRequest>of() : incoming;
        var removed = removals == null ? java.util.List.<com.enterprise.mm.payload.PurchaseRequestItemRemovalRequest>of() : removals;
        // Validate the complete command before changing a row. Omitted rows are
        // not deletions: editors may load a page or submit only changed rows.
        for (var request : updates) {
            if (request.getId() == null) {
                if (request.getVersion() != null) invalidIdentity();
                continue;
            }
            PurchaseRequestItem item = existing.get(request.getId());
            if (item == null || !touched.add(request.getId())) invalidIdentity();
            requireVersion(item, request.getVersion());
        }
        for (var request : removed) {
            PurchaseRequestItem item = existing.get(request.id());
            if (item == null || !touched.add(request.id())) invalidIdentity();
            requireVersion(item, request.version());
        }
        for (var request : updates) {
            if (request.getId() == null) {
                request.setPurchaseRequestId(parentId);
                create(request);
            } else {
                update(request.getId(), nestedUpdate(existing.get(request.getId()), request));
            }
        }
        for (var request : removed) softDelete(request.id());
    }

    private PurchaseRequestItemUpdateRequest nestedUpdate(PurchaseRequestItem item, PurchaseRequestItemCreateRequest request) {
        PurchaseRequestItemUpdateRequest update = new PurchaseRequestItemUpdateRequest();
        // Nested editors expose only part of a line. Keep existing identity,
        // service metadata and source-owned date rather than recreating the row.
        org.springframework.beans.BeanUtils.copyProperties(item, update);
        update.setLineType(item.getLineType() == null ? null : item.getLineType().name());
        if (request.getCode() != null) update.setCode(request.getCode());
        if (request.getName() != null) update.setName(request.getName());
        if (request.getDescription() != null) update.setDescription(request.getDescription());
        if (request.getLineType() != null) update.setLineType(request.getLineType());
        if (request.getMaterialId() != null) update.setMaterialId(request.getMaterialId());
        if (request.getMaterialTypeSnapshot() != null) update.setMaterialTypeSnapshot(request.getMaterialTypeSnapshot());
        if (request.getServiceRequestId() != null) update.setServiceRequestId(request.getServiceRequestId());
        if (request.getServiceDescription() != null) update.setServiceDescription(request.getServiceDescription());
        if (request.getServiceCategory() != null) update.setServiceCategory(request.getServiceCategory());
        if (request.getUnit() != null) update.setUnit(request.getUnit());
        if (request.getPeriod() != null) update.setPeriod(request.getPeriod());
        if (request.getStage() != null) update.setStage(request.getStage());
        update.setQuantity(request.getQuantity());
        update.setEstimatedPrice(request.getEstimatedPrice());
        update.setBudgetItemId(request.getBudgetItemId());
        update.setVersion(request.getVersion());
        return update;
    }

    private void requireVersion(PurchaseRequestItem item, Long version) {
        if (version == null || !Objects.equals(version, item.getVersion())) {
            throw new org.springframework.dao.OptimisticLockingFailureException("Purchase request line changed; reload before saving");
        }
    }

    private void invalidIdentity() {
        throw new BusinessException("Line identity is invalid, duplicated or belongs to another request", "purchaseRequest.items.invalidIdentity");
    }

    private boolean hasDemandSource(PurchaseRequestItem item) {
        if (item.getId() == null) return false;
        var ids = java.util.List.of(item.getId());
        return !demandAllocationRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), item.getCompanyId(), ids).isEmpty()
            || !demandItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdInAndIsDeletedFalse(
                TenantContext.currentTenant(), item.getCompanyId(), ids).isEmpty();
    }

    private void assertSourceFieldsUnchanged(PurchaseRequestItem item, PurchaseRequestItemUpdateRequest request) {
        if (!hasDemandSource(item)) return;
        boolean changed = !Objects.equals(item.getMaterialId(), request.getMaterialId())
            || !Objects.equals(item.getBudgetItemId(), request.getBudgetItemId())
            || !Objects.equals(item.getServiceRequestId(), request.getServiceRequestId())
            || !Objects.equals(normalizedUnit(item.getUnit()), normalizedUnit(request.getUnit()))
            || !Objects.equals(item.getLineType(), procurementLineValidationService.normalize(request.getLineType()))
            || item.getQuantity() == null || request.getQuantity() == null
            || item.getQuantity().compareTo(request.getQuantity()) != 0;
        if (changed) {
            throw new BusinessException("Approved demand identity, quantity and budget cannot be changed from a PR line", "purchaseRequest.items.sourceProtected");
        }
    }

    private String normalizedUnit(String unit) {
        return unit == null || unit.isBlank() ? null : unit.trim().toUpperCase(Locale.ROOT);
    }

    private void validateDocumentCategory(PurchaseRequest parent, PurchaseRequestItem candidate) {
        var snapshots = new ArrayList<MaterialType>();
        for (PurchaseRequestItem item : repository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), parent.getId())) {
            if (Objects.equals(item.getId(), candidate.getId())
                || !Objects.equals(parent.getCompanyId(), item.getCompanyId())) {
                continue;
            }
            if (item.getMaterialTypeSnapshot() == null) {
                materialTypeSnapshotService.applySnapshot(item, parent.getRequestType());
            }
            snapshots.add(item.getMaterialTypeSnapshot());
        }
        snapshots.add(candidate.getMaterialTypeSnapshot());
        documentCategoryValidator.validateNoMixedGoodsBusinessTypes("purchaseRequest", snapshots);
    }

    private PurchaseRequestItemResponse toResponse(PurchaseRequestItem entity) {
        PurchaseRequestItemResponse response = mapper.toResponse(entity);
        return procurementLineageEnricher.enrichPurchaseRequestItem(response);
    }
}

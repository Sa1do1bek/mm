package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.PurchaseOrderItemResponse;
import com.enterprise.mm.payload.PurchaseOrderItemUpdateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.PurchaseOrderItemMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.PurchaseOrderItemSearchRequest;

@Service
@RequiredArgsConstructor
public class PurchaseOrderItemService {
    private final PurchaseOrderItemRepository repository;
    private final PurchaseOrderItemMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final ProcurementLineValidationService procurementLineValidationService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final RoleDataScopeService roleDataScopeService;
    private final String entityName = "PurchaseOrderItem";
    private final AuditService auditService;
    private final MaterialCostObservationService materialCostObservationService;
    private final ProcurementLineageEnricher procurementLineageEnricher;
    private final ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    private final ProcurementDocumentCategoryValidator documentCategoryValidator;
    private final PurchaseOrderReceivingModeService receivingModeService;
    @Transactional(readOnly = true)
    public Page<PurchaseOrderItemResponse> search(PurchaseOrderItemSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            PurchaseOrderItem.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(this::toResponse);
    }
    @Transactional(readOnly = true)
    public PurchaseOrderItemResponse get(UUID id) {
        return toResponse(findEntity(id));
    }
    @Transactional
    public PurchaseOrderItemResponse create(PurchaseOrderItemCreateRequest dto) {
        PurchaseOrderItem entity = mapper.toEntity(dto);
        PurchaseOrder parent = requireMutableParent(entity.getPurchaseOrderId());
        requireGoodsMaterial(parent, entity);
        beforeCreate(entity, dto, parent);
        PurchaseOrderItem saved = repository.save(entity);
        recordCostObservation(saved, parent);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public PurchaseOrderItemResponse update(UUID id, PurchaseOrderItemUpdateRequest dto) {
        PurchaseOrderItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        UUID purchaseOrderId = entity.getPurchaseOrderId();
        PurchaseOrder parent = requireMutableParent(purchaseOrderId);
        if (!Objects.equals(purchaseOrderId, dto.getPurchaseOrderId())) {
            throw new BusinessException("Purchase order item cannot be moved to another purchase order");
        }
        String status = entity.getStatus();
        UUID serviceOrderId = entity.getServiceOrderId();
        UUID serviceActId = entity.getServiceActId();
        var receivedQuantity = entity.getReceivedQuantity();
        var rejectedQuantity = entity.getRejectedQuantity();
        var damagedQuantity = entity.getDamagedQuantity();
        String shortageReason = entity.getShortageReason();
        String receiptBatchNumber = entity.getReceiptBatchNumber();
        UUID receiptWarehouseId = entity.getReceiptWarehouseId();
        UUID receiptStorageLocationId = entity.getReceiptStorageLocationId();
        String qualityStatus = entity.getQualityStatus();
        String accreditationWarning = entity.getAccreditationWarning();
        mapper.updateEntity(entity, dto);
        requireGoodsMaterial(parent, entity);
        entity.setPurchaseOrderId(purchaseOrderId);
        entity.setStatus(status);
        entity.setServiceOrderId(serviceOrderId);
        entity.setServiceActId(serviceActId);
        entity.setReceivedQuantity(receivedQuantity);
        entity.setRejectedQuantity(rejectedQuantity);
        entity.setDamagedQuantity(damagedQuantity);
        entity.setShortageReason(shortageReason);
        entity.setReceiptBatchNumber(receiptBatchNumber);
        entity.setReceiptWarehouseId(receiptWarehouseId);
        entity.setReceiptStorageLocationId(receiptStorageLocationId);
        entity.setQualityStatus(qualityStatus);
        entity.setAccreditationWarning(accreditationWarning);
        beforeUpdate(entity, dto, parent);
        PurchaseOrderItem saved = repository.save(entity);
        recordCostObservation(saved, parent);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        PurchaseOrderItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutableParent(entity.getPurchaseOrderId());
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public PurchaseOrderItemResponse restore(UUID id) {
        PurchaseOrderItem entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutableParent(entity.getPurchaseOrderId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        PurchaseOrderItem saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected PurchaseOrderItem findEntity(UUID id) {
        PurchaseOrderItem entity = repository.findByIdAndIsDeletedFalse(id)
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
    protected void beforeCreate(PurchaseOrderItem entity, PurchaseOrderItemCreateRequest dto, PurchaseOrder parent) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        procurementLineValidationService.validateLine(dto.getLineType(), entity.getMaterialId(), entity.getServiceDescription(), entity.getQuantity(), "purchaseOrderItem");
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            StockUomPolicy.purchasing(materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId"), entity.getUnit(), entity.getQuantity());
        }
        materialTypeSnapshotService.applySnapshot(entity, parent.getRequestType());
        validateDocumentCategory(parent, entity);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.SERVICE ? "AWAITING_SERVICE_ACT" : "ACTIVE");
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
    protected void beforeUpdate(PurchaseOrderItem entity, PurchaseOrderItemUpdateRequest dto, PurchaseOrder parent) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        procurementLineValidationService.validateLine(dto.getLineType(), entity.getMaterialId(), entity.getServiceDescription(), entity.getQuantity(), "purchaseOrderItem");
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            StockUomPolicy.purchasing(materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId"), entity.getUnit(), entity.getQuantity());
        }
        materialTypeSnapshotService.applySnapshot(entity, parent.getRequestType());
        validateDocumentCategory(parent, entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.SERVICE && entity.getServiceActId() != null) {
            entity.setStatus("SERVICE_ACT_LINKED");
        }
    }

    private PurchaseOrder requireMutableParent(UUID id) {
        PurchaseOrder parent = id == null ? null : purchaseOrderRepository.findByIdAndIsDeletedFalse(id)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                || com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElse(null);
        if (parent == null) {
            throw new NotFoundException("PurchaseOrder not found: " + id);
        }
        var purchaseRequest = parent.getPurchaseRequest();
        if (!roleDataScopeService.canAccessDocument(
                parent.getCreatedBy(),
                purchaseRequest == null ? null : purchaseRequest.getRequestedBy(),
                purchaseRequest == null ? null : purchaseRequest.getDepartmentId()
            )) {
            throw new NotFoundException("PurchaseOrder not found: " + id);
        }
        if (!"DRAFT".equals(parent.getStatus()) && !"RETURNED".equals(parent.getStatus())) {
            throw new BusinessException("Purchase order items can be changed only while parent is DRAFT or RETURNED");
        }
        rejectAwardFinalizedLineMutation(parent);
        return parent;
    }

    private void rejectAwardFinalizedLineMutation(PurchaseOrder parent) {
        if (parent == null || parent.getId() == null || procurementAwardOutcomeRepository == null) {
            return;
        }
        boolean finalizedAwardExists = procurementAwardOutcomeRepository
            .existsByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                parent.getCompanyId(),
                parent.getId()
        );
        if (finalizedAwardExists) {
            throw new BusinessException("Award-finalized purchase order items cannot be changed; create an amendment instead");
        }
    }

    private void requireGoodsMaterial(PurchaseOrder parent, PurchaseOrderItem item) {
        if (parent != null
            && (parent.getRequestType() == null || parent.getRequestType() == RequestType.GOODS)
            && item != null
            && item.getMaterialId() == null) {
            throw new BusinessException(ProcurementLineValidationService.GOODS_PO_ITEM_REQUIRES_MATERIAL);
        }
    }

    private void recordCostObservation(PurchaseOrderItem item, PurchaseOrder parent) {
        if (item.getLineType() != com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            return;
        }
        materialCostObservationService.recordPurchaseOrder(
            item.getMaterialId(),
            item.getQuantity(),
            item.getUnitPrice(),
            parent.getCurrencyCode(),
            parent.getId(),
            item.getId(),
            parent.getCode(),
            parent.getSupplierId(),
            parent.getOrderDate()
        );
    }
    protected boolean companyScoped() {
        return true;
    }

    private void validateDocumentCategory(PurchaseOrder parent, PurchaseOrderItem candidate) {
        var snapshots = new ArrayList<MaterialType>();
        var receivingItems = new ArrayList<PurchaseOrderItem>();
        for (PurchaseOrderItem item : repository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), parent.getId())) {
            if (Objects.equals(item.getId(), candidate.getId())
                || !Objects.equals(parent.getCompanyId(), item.getCompanyId())) {
                continue;
            }
            if (item.getMaterialTypeSnapshot() == null) {
                materialTypeSnapshotService.applySnapshot(item, parent.getRequestType());
            }
            snapshots.add(item.getMaterialTypeSnapshot());
            receivingItems.add(item);
        }
        snapshots.add(candidate.getMaterialTypeSnapshot());
        receivingItems.add(candidate);
        documentCategoryValidator.validateNoMixedGoodsBusinessTypes("purchaseOrder", snapshots);
        receivingModeService.requireSingleReceivingMode("purchaseOrder", receivingItems);
    }

    private PurchaseOrderItemResponse toResponse(PurchaseOrderItem entity) {
        PurchaseOrderItemResponse response = mapper.toResponse(entity);
        return procurementLineageEnricher.enrichPurchaseOrderItem(response);
    }
}

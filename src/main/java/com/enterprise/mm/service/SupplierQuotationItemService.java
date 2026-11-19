package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierQuotationItemResponse;
import com.enterprise.mm.payload.SupplierQuotationItemUpdateRequest;
import com.enterprise.mm.payload.SupplierQuotationItemCreateRequest;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierQuotationItemMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.SupplierQuotationItemSearchRequest;

@Service
@RequiredArgsConstructor
public class SupplierQuotationItemService {
    private static final String ACTIVE_RFQ_LINE_UNIQUE_INDEX =
        "uk_supplier_quotation_items_active_rfq_line";
    private static final String DUPLICATE_RFQ_LINE_MESSAGE =
        "Quotation already contains RFQ line";

    private final SupplierQuotationItemRepository repository;
    private final SupplierQuotationItemMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final ProcurementLineValidationService procurementLineValidationService;
    private final SupplierQuotationRepository quotationRepository;
    private final RFQItemRepository rfqItemRepository;
    private final String entityName = "SupplierQuotationItem";
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final MaterialCostObservationService materialCostObservationService;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    @Transactional(readOnly = true)
    public Page<SupplierQuotationItemResponse> search(SupplierQuotationItemSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var pageRequest = dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable;
        var spec = ProcurementSearchSpecifications.build(
            SupplierQuotationItem.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        var records = repository.findAll(spec, pageRequest);
        if (!dataScope.hasDocumentScope()) {
            return records.map(mapper::toResponse);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> entity.getQuotation() != null
                && entity.getQuotation().getRfq() != null
                && entity.getQuotation().getRfq().getPurchaseRequest() != null
                && roleDataScopeService.canAccessDocument(
                    entity.getQuotation().getRfq().getPurchaseRequest().getCreatedBy(),
                    entity.getQuotation().getRfq().getPurchaseRequest().getRequestedBy(),
                    entity.getQuotation().getRfq().getPurchaseRequest().getDepartmentId()
                ),
            mapper::toResponse
        );
    }
    @Transactional(readOnly = true)
    public SupplierQuotationItemResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public SupplierQuotationItemResponse create(SupplierQuotationItemCreateRequest dto) {
        SupplierQuotationItem entity = mapper.toEntity(dto);
        SupplierQuotation parent = requireMutableParent(entity.getQuotationId());
        RFQItem rfqItem = validateSourceRfqItem(entity.getRfqItemId(), parent);
        applySourceSnapshot(entity, rfqItem);
        requireUniqueActiveRfqLine(entity.getQuotationId(), entity.getRfqItemId());
        beforeCreate(entity, dto, parent);
        SupplierQuotationItem saved = saveAndFlushWithDuplicateConflict(entity);
        recordCostObservation(saved, parent);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public SupplierQuotationItemResponse update(UUID id, SupplierQuotationItemUpdateRequest dto) {
        SupplierQuotationItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        UUID quotationId = entity.getQuotationId();
        UUID rfqItemId = entity.getRfqItemId();
        String status = entity.getStatus();
        SupplierQuotation parent = requireMutableParent(quotationId);
        if (!Objects.equals(quotationId, dto.getQuotationId())) {
            throw new BusinessException("Quotation item cannot be moved to another quotation");
        }
        if (!Objects.equals(rfqItemId, dto.getRfqItemId())) {
            throw new BusinessException("Quotation item source RFQ item cannot be changed");
        }
        RFQItem rfqItem = validateSourceRfqItem(rfqItemId, parent);
        mapper.updateEntity(entity, dto);
        entity.setQuotationId(quotationId);
        entity.setRfqItemId(rfqItemId);
        entity.setStatus(status);
        applySourceSnapshot(entity, rfqItem);
        beforeUpdate(entity, dto, parent);
        SupplierQuotationItem saved = repository.save(entity);
        recordCostObservation(saved, parent);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        SupplierQuotationItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireMutableParent(entity.getQuotationId());
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public SupplierQuotationItemResponse restore(UUID id) {
        SupplierQuotationItem entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutableParent(entity.getQuotationId());
        requireUniqueActiveRfqLine(entity.getQuotationId(), entity.getRfqItemId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierQuotationItem saved = saveAndFlushWithDuplicateConflict(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected SupplierQuotationItem findEntity(UUID id) {
        SupplierQuotationItem entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (entity.getQuotation() != null
            && entity.getQuotation().getRfq() != null
            && entity.getQuotation().getRfq().getPurchaseRequest() != null
            && !roleDataScopeService.canAccessDocument(
                entity.getQuotation().getRfq().getPurchaseRequest().getCreatedBy(),
                entity.getQuotation().getRfq().getPurchaseRequest().getRequestedBy(),
                entity.getQuotation().getRfq().getPurchaseRequest().getDepartmentId()
            )) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected void beforeCreate(SupplierQuotationItem entity, SupplierQuotationItemCreateRequest dto, SupplierQuotation parent) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        procurementLineValidationService.validateLine(dto.getLineType(), entity.getMaterialId(), entity.getServiceDescription(), entity.getQuantity(), "supplierQuotationItem");
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        }
        materialTypeSnapshotService.applySnapshot(entity, requestType(parent));
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
    protected void beforeUpdate(SupplierQuotationItem entity, SupplierQuotationItemUpdateRequest dto, SupplierQuotation parent) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        procurementLineValidationService.validateLine(dto.getLineType(), entity.getMaterialId(), entity.getServiceDescription(), entity.getQuantity(), "supplierQuotationItem");
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        }
        materialTypeSnapshotService.applySnapshot(entity, requestType(parent));
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private SupplierQuotation requireMutableParent(UUID id) {
        SupplierQuotation parent = id == null ? null : quotationRepository.findByIdAndIsDeletedFalse(id)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                || com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElse(null);
        if (parent == null) {
            throw new NotFoundException("SupplierQuotation not found: " + id);
        }
        var rfq = parent.getRfq();
        var purchaseRequest = rfq == null ? null : rfq.getPurchaseRequest();
        if (purchaseRequest == null || !roleDataScopeService.canAccessDocument(
                purchaseRequest.getCreatedBy(),
                purchaseRequest.getRequestedBy(),
                purchaseRequest.getDepartmentId()
            )) {
            throw new NotFoundException("SupplierQuotation not found: " + id);
        }
        if ("SELECTED".equals(parent.getStatus()) || "REJECTED".equals(parent.getStatus())) {
            throw new BusinessException("Selected or rejected quotation items cannot be changed");
        }
        return parent;
    }

    private RFQItem validateSourceRfqItem(UUID rfqItemId, SupplierQuotation quotation) {
        if (rfqItemId == null) {
            return null;
        }
        var rfqItem = rfqItemRepository.findByIdAndIsDeletedFalse(rfqItemId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> Objects.equals(quotation.getCompanyId(), candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("RFQItem not found: " + rfqItemId));
        if (!Objects.equals(quotation.getRfqId(), rfqItem.getRfqId())) {
            throw new BusinessException("RFQ item does not belong to quotation RFQ");
        }
        return rfqItem;
    }

    private void applySourceSnapshot(SupplierQuotationItem entity, RFQItem rfqItem) {
        if (rfqItem == null || rfqItem.getMaterialTypeSnapshot() == null) {
            return;
        }
        if (entity.getMaterialTypeSnapshot() != null && entity.getMaterialTypeSnapshot() != rfqItem.getMaterialTypeSnapshot()) {
            throw new BusinessException(
                "MATERIAL_TYPE_SNAPSHOT_MISMATCH: supplierQuotationItem snapshot does not match RFQ item snapshot",
                "material.type.snapshot_mismatch",
                "supplierQuotationItem",
                entity.getMaterialTypeSnapshot(),
                rfqItem.getMaterialTypeSnapshot()
            );
        }
        entity.setMaterialTypeSnapshot(rfqItem.getMaterialTypeSnapshot());
    }

    private void requireUniqueActiveRfqLine(UUID quotationId, UUID rfqItemId) {
        if (quotationId != null
            && rfqItemId != null
            && repository.existsByQuotationIdAndRfqItemIdAndIsDeletedFalse(quotationId, rfqItemId)) {
            throw duplicateRfqLine(rfqItemId);
        }
    }

    private SupplierQuotationItem saveAndFlushWithDuplicateConflict(SupplierQuotationItem entity) {
        try {
            SupplierQuotationItem saved = repository.save(entity);
            repository.flush();
            return saved;
        } catch (DataIntegrityViolationException ex) {
            if (causedByConstraint(ex, ACTIVE_RFQ_LINE_UNIQUE_INDEX)) {
                throw duplicateRfqLine(entity.getRfqItemId());
            }
            throw ex;
        }
    }

    private ConflictException duplicateRfqLine(UUID rfqItemId) {
        return new ConflictException(
            DUPLICATE_RFQ_LINE_MESSAGE + ": " + rfqItemId,
            "supplier.quotation.line.duplicate",
            rfqItemId
        );
    }

    private boolean causedByConstraint(Throwable throwable, String constraintName) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation
                && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    private void recordCostObservation(SupplierQuotationItem item, SupplierQuotation parent) {
        if (item.getLineType() != com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            return;
        }
        materialCostObservationService.recordSupplierQuotation(
            item.getMaterialId(),
            item.getQuantity(),
            item.getUnitPrice(),
            parent.getCurrencyCode(),
            parent.getId(),
            item.getId(),
            parent.getCode(),
            parent.getSupplierId(),
            parent.getSubmittedAt()
        );
    }

    protected boolean companyScoped() {
        return true;
    }

    private RequestType requestType(SupplierQuotation quotation) {
        PurchaseRequest purchaseRequest = quotation == null || quotation.getRfq() == null
            ? null
            : quotation.getRfq().getPurchaseRequest();
        return purchaseRequest == null || purchaseRequest.getRequestType() == null
            ? RequestType.GOODS
            : purchaseRequest.getRequestType();
    }
}

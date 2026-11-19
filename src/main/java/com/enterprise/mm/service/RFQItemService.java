package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.RFQItemResponse;
import com.enterprise.mm.payload.RFQItemUpdateRequest;
import com.enterprise.mm.payload.RFQItemCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.RFQItemMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.RFQItemSearchRequest;

@Service
@RequiredArgsConstructor
public class RFQItemService {
    private final RFQItemRepository repository;
    private final RFQItemMapper mapper;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final ProcurementLineValidationService procurementLineValidationService;
    private final RFQRepository rfqRepository;
    private final String entityName = "RFQItem";
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final RfqAggregationService rfqAggregationService;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;
    @Transactional(readOnly = true)
    public Page<RFQItemResponse> search(RFQItemSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var pageRequest = dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable;
        var spec = ProcurementSearchSpecifications.build(
            RFQItem.class,
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
            entity -> entity.getRfq() != null
                && entity.getRfq().getPurchaseRequest() != null
                && roleDataScopeService.canAccessDocument(
                    entity.getRfq().getPurchaseRequest().getCreatedBy(),
                    entity.getRfq().getPurchaseRequest().getRequestedBy(),
                    entity.getRfq().getPurchaseRequest().getDepartmentId()
                ),
            mapper::toResponse
        );
    }
    @Transactional(readOnly = true)
    public RFQItemResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public RFQItemResponse create(RFQItemCreateRequest dto) {
        RFQItem entity = mapper.toEntity(dto);
        RFQ parent = requireMutableParent(entity.getRfqId());
        beforeCreate(entity, dto, parent);
        RFQItem saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public RFQItemResponse update(UUID id, RFQItemUpdateRequest dto) {
        RFQItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        rfqAggregationService.assertRfqItemMutable(id);
        UUID rfqId = entity.getRfqId();
        String status = entity.getStatus();
        RFQ parent = requireMutableParent(rfqId);
        if (!Objects.equals(rfqId, dto.getRfqId())) {
            throw new BusinessException("RFQ item cannot be moved to another RFQ");
        }
        mapper.updateEntity(entity, dto);
        entity.setRfqId(rfqId);
        entity.setStatus(status);
        beforeUpdate(entity, dto, parent);
        RFQItem saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        RFQItem entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        rfqAggregationService.assertRfqItemMutable(id);
        requireMutableParent(entity.getRfqId());
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public RFQItemResponse restore(UUID id) {
        RFQItem entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        requireMutableParent(entity.getRfqId());
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        RFQItem saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected RFQItem findEntity(UUID id) {
        RFQItem entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (!canAccessRfqItemSources(entity)) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected void beforeCreate(RFQItem entity, RFQItemCreateRequest dto, RFQ parent) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        procurementLineValidationService.validateLine(dto.getLineType(), entity.getMaterialId(), entity.getServiceDescription(), entity.getQuantity(), "rfqItem");
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
    protected void beforeUpdate(RFQItem entity, RFQItemUpdateRequest dto, RFQ parent) {
        entity.setLineType(procurementLineValidationService.normalize(dto.getLineType()));
        procurementLineValidationService.validateLine(dto.getLineType(), entity.getMaterialId(), entity.getServiceDescription(), entity.getQuantity(), "rfqItem");
        if (entity.getLineType() == com.enterprise.mm.enums.ProcurementLineType.MATERIAL) {
            materialCatalogGuard.requireActiveMaterial(entity.getMaterialId(), "materialId");
        }
        materialTypeSnapshotService.applySnapshot(entity, requestType(parent));
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
    }

    private RFQ requireMutableParent(UUID id) {
        RFQ parent = id == null ? null : rfqRepository.findByIdAndIsDeletedFalse(id)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                || com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElse(null);
        if (parent == null) {
            throw new NotFoundException("RFQ not found: " + id);
        }
        var purchaseRequest = parent.getPurchaseRequest();
        if (purchaseRequest == null && parent.getPurchaseRequestId() != null) {
            purchaseRequest = purchaseRequestRepository.findByIdAndIsDeletedFalse(parent.getPurchaseRequestId())
                .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
                .filter(candidate -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                    || com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
                .orElse(null);
            parent.setPurchaseRequest(purchaseRequest);
        }
        if (purchaseRequest == null || !roleDataScopeService.canAccessDocument(
                purchaseRequest.getCreatedBy(),
                purchaseRequest.getRequestedBy(),
                purchaseRequest.getDepartmentId()
            )) {
            throw new NotFoundException("RFQ not found: " + id);
        }
        if (!"DRAFT".equals(parent.getStatus()) && !"AMENDMENT_DRAFT".equals(parent.getStatus())) {
            throw new BusinessException("RFQ items can be changed only while parent is DRAFT or AMENDMENT_DRAFT");
        }
        return parent;
    }
    protected boolean companyScoped() {
        return true;
    }

    private RequestType requestType(RFQ rfq) {
        PurchaseRequest purchaseRequest = rfq == null ? null : rfq.getPurchaseRequest();
        return purchaseRequest == null || purchaseRequest.getRequestType() == null
            ? RequestType.GOODS
            : purchaseRequest.getRequestType();
    }

    private boolean canAccessRfqItemSources(RFQItem entity) {
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        if (!scope.hasDocumentScope()) {
            return true;
        }
        var sources = sourceRepository.findByTenantIdAndCompanyIdAndRfqItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getId());
        if (sources.isEmpty()) {
            return entity.getRfq() == null
                || entity.getRfq().getPurchaseRequest() == null
                || roleDataScopeService.canAccessDocument(
                    entity.getRfq().getPurchaseRequest().getCreatedBy(),
                    entity.getRfq().getPurchaseRequest().getRequestedBy(),
                    entity.getRfq().getPurchaseRequest().getDepartmentId());
        }
        var purchaseRequestIds = sources.stream()
            .map(source -> source.getPurchaseRequestId())
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        var purchaseRequests = purchaseRequestRepository.findAllById(purchaseRequestIds);
        if (purchaseRequests.size() != purchaseRequestIds.size()) {
            return false;
        }
        for (PurchaseRequest purchaseRequest : purchaseRequests) {
            if (!roleDataScopeService.canAccessDocument(
                purchaseRequest.getCreatedBy(),
                purchaseRequest.getRequestedBy(),
                purchaseRequest.getDepartmentId())) {
                return false;
            }
        }
        return true;
    }
}

package com.enterprise.mm.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.mapper.PurchaseRequestMapper;
import com.enterprise.mm.mapper.RFQMapper;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.PurchaseRequestCreateRequest;
import com.enterprise.mm.payload.PurchaseRequestResponse;
import com.enterprise.mm.payload.PurchaseRequestSearchRequest;
import com.enterprise.mm.payload.PurchaseRequestUpdateRequest;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import com.enterprise.mm.util.BusinessNameGenerator;

@Service
@Slf4j
public class PurchaseRequestService {
    private final PurchaseRequestRepository repository;
    private final PurchaseRequestMapper mapper;
    private final String entityName = "PurchaseRequest";
    private final PurchaseRequestItemRepository itemRepository;
    private final PurchaseRequestItemService itemService;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository rfqItemRepository;
    private final RFQMapper rfqMapper;
    private final SupplierQuotationRepository quotationRepository;
    private final SupplierQuotationItemRepository quotationItemRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final RFQPurchaseRequestSourceRepository rfqPurchaseRequestSourceRepository;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final ApprovalEngineService approvalEngineService;
    private final RfqAggregationService rfqAggregationService;
    private final SupplierAccreditationService supplierAccreditationService;
    private final MaterialRepository materialRepository;
    private final MaterialCostObservationService materialCostObservationService;
    private final ProcurementLineageEnricher procurementLineageEnricher;
    private final BudgetItemService budgetItemService;

    private final PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    private final PurchaseRequestActionPolicy purchaseRequestActionPolicy;
    public PurchaseRequestService(
        PurchaseRequestRepository repository,
        PurchaseRequestMapper mapper,
        PurchaseRequestItemRepository itemRepository,
        PurchaseRequestItemService itemService,
        RFQRepository rfqRepository,
        RFQItemRepository rfqItemRepository,
        RFQMapper rfqMapper,
        SupplierQuotationRepository quotationRepository,
        SupplierQuotationItemRepository quotationItemRepository,
        PurchaseOrderRepository purchaseOrderRepository,
        PurchaseOrderItemRepository purchaseOrderItemRepository,
        PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository,
        PurchaseOrderMapper purchaseOrderMapper,
        RFQPurchaseRequestSourceRepository rfqPurchaseRequestSourceRepository,
        IntegrationEventService integrationEventService,
        AuditService auditService,
        RoleDataScopeService roleDataScopeService,
        ApprovalEngineService approvalEngineService,
        RfqAggregationService rfqAggregationService,
        SupplierAccreditationService supplierAccreditationService,
        MaterialRepository materialRepository,
        MaterialCostObservationService materialCostObservationService,
        ProcurementLineageEnricher procurementLineageEnricher,
        BudgetItemService budgetItemService,
        PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy,
        PurchaseRequestActionPolicy purchaseRequestActionPolicy
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.itemRepository = itemRepository;
        this.itemService = itemService;
        this.rfqRepository = rfqRepository;
        this.rfqItemRepository = rfqItemRepository;
        this.rfqMapper = rfqMapper;
        this.quotationRepository = quotationRepository;
        this.quotationItemRepository = quotationItemRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.purchaseOrderSourceAllocationRepository = purchaseOrderSourceAllocationRepository;
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.rfqPurchaseRequestSourceRepository = rfqPurchaseRequestSourceRepository;
        this.integrationEventService = integrationEventService;
        this.auditService = auditService;
        this.roleDataScopeService = roleDataScopeService;
        this.approvalEngineService = approvalEngineService;
        this.rfqAggregationService = rfqAggregationService;
        this.supplierAccreditationService = supplierAccreditationService;
        this.materialRepository = materialRepository;
        this.materialCostObservationService = materialCostObservationService;
        this.procurementLineageEnricher = procurementLineageEnricher;
        this.budgetItemService = budgetItemService;
        this.purchaseOrderDemandCyclePolicy = purchaseOrderDemandCyclePolicy;
        this.purchaseRequestActionPolicy = purchaseRequestActionPolicy;
    }
    protected void beforeCreate(PurchaseRequest entity, PurchaseRequestCreateRequest request) {
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
            "Purchase request",
            entity.getName(),
            entity.getCode()
        ));
        entity.setStatus(PurchaseRequestStatus.DRAFT.name());
        if (entity.getRequestType() == null) {
            entity.setRequestType(RequestType.GOODS);
        }
        if (entity.getRequiredDate() == null) {
            entity.setRequiredDate(LocalDate.now().plusDays(14));
        }
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(entity.getDepartmentId(), "Purchase request");
    }
    @Transactional
    public PurchaseRequestResponse submit(UUID id) {
        PurchaseRequest request = findEntity(id);
        purchaseRequestActionPolicy.requireActionAllowed(request, com.enterprise.mm.enums.PurchaseRequestWorkspaceActionKey.SUBMIT);
        requireDemandPlanLineageForSubmission(request);
        approvalEngineService.start(PurchaseRequestApprovalAdapter.MODULE_KEY, id);
        return toResponse(findEntity(id));
    }
    @Transactional
    public PurchaseRequestResponse approve(UUID id, String comment) {
        throw new BusinessException("Purchase request approval must be completed through approval task endpoints");
    }
    @Transactional
    public PurchaseRequestResponse reject(UUID id, String comment) {
        throw new BusinessException("Purchase request rejection must be completed through approval task endpoints");
    }
    @Transactional
    public PurchaseRequestResponse cancel(UUID id) {
        PurchaseRequest request = findEntity(id);
        purchaseRequestActionPolicy.requireActionAllowed(request, com.enterprise.mm.enums.PurchaseRequestWorkspaceActionKey.CANCEL);
        rfqAggregationService.assertPurchaseRequestMutable(id);
        if (PurchaseRequestStatus.PO_CREATED.name().equals(request.getStatus())
                || PurchaseRequestStatus.CONVERTED_TO_PO.name().equals(request.getStatus())) {
            throw new BusinessException("Converted purchase request cannot be cancelled");
        }
        releaseBudgetReservationsForCancellation(request);
        request.setStatus(PurchaseRequestStatus.CANCELLED.name());
        auditService.log("PurchaseRequest", id, "CANCEL", request.getCode(), null, null);
        return toResponse(repository.save(request));
    }

    private void releaseBudgetReservationsForCancellation(PurchaseRequest request) {
        if (request == null || !PurchaseRequestStatus.APPROVED.name().equals(request.getStatus())) {
            return;
        }
        itemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), request.getId())
            .forEach(item -> budgetItemService.releaseIdempotent(
                item.getBudgetItemId(),
                nvl(item.getEstimatedPrice()).multiply(nvl(item.getQuantity())),
                "PurchaseRequest " + request.getCode(),
                "PurchaseRequest",
                request.getId(),
                item.getId(),
                "PurchaseRequestItem:" + item.getId(),
                "RELEASE_CANCELLED"
            ));
    }
    @Transactional
    public RFQResponse createRfq(UUID purchaseRequestId) {
        throw new BusinessException(
            "Approved purchase requests must create a procurement method decision before sourcing",
            "procurement.pmd.required",
            purchaseRequestId
        );
    }
    @Transactional
    public PurchaseOrderResponse convertToPo(UUID purchaseRequestId, UUID quotationId) {
        throw new BusinessException(
            "Approved purchase requests must create a procurement method decision before purchase order conversion",
            "procurement.pmd.required",
            purchaseRequestId
        );
    }

    private void requireStatus(PurchaseRequest request, PurchaseRequestStatus status) {
        if (!status.name().equals(request.getStatus())) {
            throw new BusinessException("Purchase request must be " + status + ", current status is " + request.getStatus());
        }
    }
    @Transactional(readOnly = true)
    public Page<PurchaseRequestResponse> search(PurchaseRequestSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var pageRequest = dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable;
        var spec = ProcurementSearchSpecifications.build(
            PurchaseRequest.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        var records = repository.findAll(spec, pageRequest);
        if (!dataScope.hasDocumentScope()) {
            return records.map(this::toResponse);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> roleDataScopeService.canAccessDocument(
                entity.getCreatedBy(),
                entity.getRequestedBy(),
                entity.getDepartmentId()
            ),
            this::toResponse
        );
    }
    @Transactional(readOnly = true)
    public PurchaseRequestResponse get(UUID id) {
        return toResponse(findEntity(id));
    }
    @Transactional
    public PurchaseRequestResponse create(PurchaseRequestCreateRequest request) {
        PurchaseRequest entity = mapper.toEntity(request);
        recalculateTotals(entity, request);
        beforeCreate(entity, request);
        PurchaseRequest saved = repository.save(entity);
        saveSubmittedItems(saved.getId(), request.getItems());
        linkApprovalFlowIfConfigured(saved);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }
    @Transactional
    public PurchaseRequestResponse update(UUID id, PurchaseRequestUpdateRequest request) {
        PurchaseRequest entity = findEntity(id);
        purchaseRequestActionPolicy.requireActionAllowed(entity, com.enterprise.mm.enums.PurchaseRequestWorkspaceActionKey.EDIT);
        String oldSnapshot = auditService.snapshot(entity);
        if (!PurchaseRequestStatus.DRAFT.name().equals(entity.getStatus())
                && !PurchaseRequestStatus.RETURNED.name().equals(entity.getStatus())) {
            throw new BusinessException("Purchase request can be edited only in DRAFT or RETURNED status");
        }
        String previousStatus = entity.getStatus();
        BigDecimal previousTotal = entity.getTotalAmount();
        if ((request.getItems() != null || request.getRemovedItems() != null)
            && !"IDENTITY_PATCH".equals(request.getItemUpdateMode())) {
            throw new BusinessException("Reload the updated purchase request editor before saving lines", "purchaseRequest.items.reloadEditor");
        }
        if (request.getVersion() != null || request.getItems() != null || request.getRemovedItems() != null) {
            if (!Objects.equals(entity.getVersion(), request.getVersion())) {
                throw new org.springframework.dao.OptimisticLockingFailureException("Purchase request changed; reload before saving");
            }
        }
        if (!Objects.equals(entity.getDemandPlanId(), request.getDemandPlanId())) {
            throw new BusinessException("The source demand plan cannot be changed", "purchaseRequest.items.sourceProtected");
        }
        if (entity.getDemandPlanId() != null) {
            LocalDate sourceDate = itemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), entity.getId()).stream()
                .map(PurchaseRequestItem::getRequiredDate).filter(Objects::nonNull).min(LocalDate::compareTo)
                .orElse(entity.getRequiredDate());
            if (!Objects.equals(sourceDate, request.getRequiredDate())) {
                throw new BusinessException("The header date summarizes source line deadlines", "purchaseRequest.items.sourceProtected");
            }
        }
        assertProtectedFieldsMutable(entity, request);
        mapper.updateEntity(entity, request);
        entity.setStatus(previousStatus);
        entity.setTotalAmount(previousTotal);
        beforeUpdate(entity, request);
        PurchaseRequest saved = repository.save(entity);
        itemService.synchronizeItems(saved.getId(), request.getItems(), request.getRemovedItems());
        if (request.getItems() != null || request.getRemovedItems() != null) {
            var savedItems = itemRepository.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), saved.getId());
            saved.setTotalAmount(savedItems.stream().map(item -> nvl(item.getQuantity()).multiply(nvl(item.getEstimatedPrice())))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
            savedItems.stream().map(PurchaseRequestItem::getRequiredDate).filter(Objects::nonNull).min(LocalDate::compareTo)
                .ifPresent(saved::setRequiredDate);
            repository.save(saved);
        }
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        PurchaseRequest entity = findEntity(id);
        purchaseRequestActionPolicy.requireActionAllowed(entity, com.enterprise.mm.enums.PurchaseRequestWorkspaceActionKey.DELETE);
        String oldSnapshot = auditService.snapshot(entity);
        rfqAggregationService.assertPurchaseRequestMutable(id);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public PurchaseRequestResponse restore(UUID id) {
        PurchaseRequest entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), entity.getRequestedBy(), entity.getDepartmentId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        PurchaseRequest saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    protected PurchaseRequest findEntity(UUID id) {
        PurchaseRequest entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (!roleDataScopeService.canAccessDocument(entity.getCreatedBy(), entity.getRequestedBy(), entity.getDepartmentId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected void beforeUpdate(PurchaseRequest entity, PurchaseRequestUpdateRequest request) {
        rejectDerivedTenderStatus(request.getStatus());
        if (entity.getRequestType() == null) {
            entity.setRequestType(RequestType.GOODS);
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        roleDataScopeService.assertDepartmentDocumentMutationAllowed(entity.getDepartmentId(), "Purchase request");
    }
    protected boolean companyScoped() {
        return true;
    }

    private PurchaseRequestResponse toResponse(PurchaseRequest entity) {
        PurchaseRequestResponse response = mapper.toResponse(entity);
        return procurementLineageEnricher.enrichPurchaseRequest(response);
    }

    private void saveSubmittedItems(UUID purchaseRequestId, java.util.List<com.enterprise.mm.payload.PurchaseRequestItemCreateRequest> items) {
        if (items == null) {
            return;
        }
        items.forEach(item -> {
            item.setPurchaseRequestId(purchaseRequestId);
            itemService.create(item);
        });
    }

    private void recalculateTotals(PurchaseRequest entity, PurchaseRequestCreateRequest request) {
        if (request.getItems() == null) {
            return;
        }
        BigDecimal total = request.getItems().stream()
            .map(item -> nvl(item.getQuantity()).multiply(nvl(item.getEstimatedPrice())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        entity.setTotalAmount(total);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void linkApprovalFlowIfConfigured(PurchaseRequest request) {
        RequestType requestType = request.getRequestType() == null ? RequestType.GOODS : request.getRequestType();
        if (!approvalEngineService.hasActiveFlow(
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            request.getCompanyId(),
            request.getDepartmentId(),
            requestType
        )) {
            log.warn(
                "No active approval flow for module PURCHASE_REQUEST and request type {}; purchase request {} created without approval link",
                requestType,
                request.getCode()
            );
            return;
        }
        approvalEngineService.linkItem(
            PurchaseRequestApprovalAdapter.MODULE_KEY,
            request.getId(),
            request.getCompanyId(),
            request.getCode()
        );
    }

    private void requireDemandPlanLineageForSubmission(PurchaseRequest request) {
        if (request.getDemandPlanId() == null) {
            throw new BusinessException("Purchase request must originate from an approved demand plan before submission");
        }
    }

    private void assertProtectedFieldsMutable(PurchaseRequest entity, PurchaseRequestUpdateRequest request) {
        rejectDerivedTenderStatus(request.getStatus());
        boolean protectedChange =
            !Objects.equals(entity.getCurrencyCode(), request.getCurrencyCode())
                || !Objects.equals(entity.getDepartmentId(), request.getDepartmentId())
                || !Objects.equals(entity.getRequiredDate(), request.getRequiredDate())
                || !Objects.equals(entity.getRequestType(), request.getRequestType() == null ? null : RequestType.valueOf(request.getRequestType().trim().toUpperCase(Locale.ROOT).replace('-', '_')));
        if (protectedChange) {
            rfqAggregationService.assertPurchaseRequestMutable(entity.getId());
        }
    }

    private void rejectDerivedTenderStatus(String status) {
        if (PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name().equals(status)
            || PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name().equals(status)) {
            throw new BusinessException("Tender allocation statuses are derived by RFQ allocation");
        }
    }
}

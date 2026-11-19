package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.PurchaseOrderUpdateRequest;
import com.enterprise.mm.payload.PurchaseOrderCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.payload.PurchaseOrderRevisionDto;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoBatchRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoLineRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceiptIntegrationOutcome;
import com.enterprise.mm.payload.BusinessActionDtos.ReceiptMovementOutcome;
import com.enterprise.mm.payload.BusinessActionDtos.ReceiptOutcome;
import com.enterprise.mm.payload.BusinessActionDtos.ReceiptQualityOutcome;
import com.enterprise.mm.payload.QualityInspectionCreateRequest;
import com.enterprise.mm.payload.QualityInspectionResponse;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort.AcceptedReceipt;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.mapper.PurchaseOrderRevisionMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.ContractInvoiceStatus;
import com.enterprise.mm.enums.ContractPaymentStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderWorkspaceActionKey;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.fulfillment.FulfillmentQuantities;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderRevision;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderRevisionRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.PurchaseOrderApprovalAdapter;
import com.enterprise.mm.procurement.command.service.PurchaseOrderDispatchReadinessService;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.supplier.po.service.SupplierPoCancellationService;
import com.enterprise.mm.supplier.po.service.SupplierPoCollaborationQueryService;
import com.enterprise.mm.supplier.po.service.SupplierPoDispatchService;
import com.enterprise.mm.supplier.po.service.SupplierPoSupersessionService;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.enterprise.mm.payload.PurchaseOrderSearchRequest;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeReceiveActionPolicy;
import com.enterprise.mm.warehouse.receiving.model.ReceiptBatchPostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptLinePostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingResult;
import com.enterprise.mm.warehouse.receiving.model.ReceiptUnitPostingDraft;
import com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService;
import com.enterprise.mm.util.BusinessNameGenerator;
import com.enterprise.mm.security.pilot.PilotCapability;
import com.enterprise.mm.security.pilot.PilotCapabilityPolicy;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {
    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);
    private final PurchaseOrderRepository repository;
    private final PurchaseOrderMapper mapper;
    private final String entityName = "PurchaseOrder";
    private final PurchaseOrderItemRepository itemRepository;
    private final PurchaseOrderItemService itemService;
    private final PurchaseOrderRevisionRepository revisionRepository;
    private final PurchaseOrderRevisionMapper revisionMapper;
    private final PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final ProcurementStockPostingPort stockPostingPort;
    private final MaterialRepository materialRepository;
    private final AdvanceShippingNoticeRepository asnRepository;
    private final AdvanceShippingNoticeItemRepository asnItemRepository;
    private final SupplierEvaluationService supplierEvaluationService;
    private final SupplierAccreditationService supplierAccreditationService;
    private final SupplierEligibilityService supplierEligibilityService;
    private final IntegrationEventService integrationEventService;
    private final ContractService contractService;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final WorkCompletionActRepository workCompletionActRepository;
    private final ServiceClaimRepository serviceClaimRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final RoleDataScopeService roleDataScopeService;
    private final ApprovalEngineService approvalEngineService;
    private final StorageBinRepository storageBinRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final WarehouseRepository warehouseRepository;
    private final PurchaseOrderDispatchReadinessService dispatchReadinessService;
    private final IdempotencyService idempotencyService;
    private final AttachmentRepository attachmentRepository;
    private final WarehouseReceiptPostingService warehouseReceiptPostingService;
    private final FakturaReceiptEvidenceService fakturaEvidence;
    private final ProcurementLineageEnricher procurementLineageEnricher;
    private final SupplierPoDispatchService supplierPoDispatchService;
    private final SupplierPoSupersessionService supplierPoSupersessionService;
    private final SupplierPoCancellationService supplierPoCancellationService;
    private final SupplierPoCollaborationQueryService supplierPoCollaborationQueryService;
    private final SupplierPoResponseRepository supplierPoResponseRepository;
    private final SupplierPoCommitmentRepository supplierPoCommitmentRepository;
    private final DeliveryScheduleRepository deliveryScheduleRepository;
    private final ProcurementPayablesNotificationService payablesNotificationService;
    private final PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    private final GoodsIncomeReceiveActionPolicy goodsIncomeReceiveActionPolicy;
    private final PurchaseOrderActionPolicy purchaseOrderActionPolicy;
    private final ContractInvoiceRepository contractInvoiceRepository;
    private final ContractPaymentRepository contractPaymentRepository;
    private final ProcurementProcedureRepository procurementProcedureRepository;
    private final ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    private final BudgetItemService budgetItemService;
    private final PurchaseOrderServiceOrderLinkService serviceOrderLinkService;
    private final QualityInspectionService qualityInspectionService;
    private final PilotCapabilityPolicy pilotCapabilityPolicy;
    private final PurchaseOrderReceivingModeService receivingModeService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.enterprise.mm.warehouse.packaging.ReturnablePackagingService returnablePackagingService;

    protected void beforeCreate(PurchaseOrder entity, PurchaseOrderCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), purchaseRequestName(entity.getPurchaseRequestId())
        ));

        entity.setStatus(PurchaseOrderStatus.DRAFT.name());
        if (entity.getRequestType() == null) {
            entity.setRequestType(resolveRequestType(entity.getPurchaseRequestId()));
        }
        normalizeCurrency(entity);
    }
    @Transactional
    public PurchaseOrderResponse update(UUID id, PurchaseOrderUpdateRequest dto) {
        PurchaseOrder po = findEntity(id);
        // UX-LIVE-04: enforce EDIT action policy
        purchaseOrderActionPolicy.requireActionAllowed(po, com.enterprise.mm.enums.PurchaseOrderWorkspaceActionKey.EDIT);
        if (!PurchaseOrderStatus.DRAFT.name().equals(po.getStatus())
                && !PurchaseOrderStatus.RETURNED.name().equals(po.getStatus())) {
            throw new BusinessException("Purchase order can be edited only in DRAFT or RETURNED status");
        }
        ObjectNode before = snapshot(po);
        String previousCode = po.getCode();
        String previousName = po.getName();
        String previousStatus = po.getStatus();
        RequestType previousRequestType = po.getRequestType();
        mapper.updateEntity(po, dto);
        po.setStatus(previousStatus);
        po.setRequestType(previousRequestType);
        recalculateTotals(po, dto);
        if (po.getCode() == null || po.getCode().isBlank()) {
            po.setCode(previousCode);
        }
        if (po.getName() == null || po.getName().isBlank()) {
            po.setName(previousName);
        }
        beforeUpdate(po, dto);
        PurchaseOrder saved = repository.save(po);
        saveSubmittedItems(saved, dto.getItems(), true);
        boolean commercialContentChanged = createRevision(saved, before, snapshot(saved), "Updated from API");
        if (commercialContentChanged) {
            // See SupplierPoSupersessionService javadoc: no code path today lets a SENT PO
            // reach update() (it's gated to DRAFT/RETURNED above), so this is a no-op in
            // practice until a future PO-revision-after-SENT workflow exists -- wired here
            // now so supersession fires automatically the moment that becomes possible.
            supplierPoSupersessionService.supersedeForPoChange(saved.getId(), "Purchase order updated");
        }
        return toResponse(saved);
    }
    @Transactional(readOnly = true)
    public List<PurchaseOrderRevisionDto> revisions(UUID purchaseOrderId) {
        findEntity(purchaseOrderId);
        return revisionRepository.findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByRevisionNumberDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                purchaseOrderId
            )
            .stream()
            .map(revisionMapper::toDto)
            .toList();
    }
    @Transactional
    public PurchaseOrderResponse submitForApproval(UUID id) {
        PurchaseOrder po = findEntity(id);
        // UX-LIVE-04: enforce SUBMIT action policy
        purchaseOrderActionPolicy.requireActionAllowed(po, PurchaseOrderWorkspaceActionKey.SUBMIT);
        List<PurchaseOrderItem> items = itemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), id);
        receivingModeService.requireSingleReceivingMode("purchaseOrder", items);
        approvalEngineService.start(PurchaseOrderApprovalAdapter.MODULE_KEY, id);
        return toResponse(repository.findByIdAndIsDeletedFalse(po.getId()).orElseThrow());
    }
    @Transactional
    public PurchaseOrderResponse send(UUID id) {
        dispatchReadinessService.requireReady(id);
        PurchaseOrder po = findEntity(id);
        // UX-LIVE-04: enforce SEND action policy
        purchaseOrderActionPolicy.requireActionAllowed(po, PurchaseOrderWorkspaceActionKey.SEND);
        boolean alreadySent = PurchaseOrderStatus.SENT.name().equals(po.getStatus());
        if (alreadySent) {
            // Idempotent replay: only safe when the current dispatch already matches the
            // PO's current version -- i.e. nothing commercial changed since the last
            // successful send. Do not re-run contract/budget consumption, do not
            // re-emit MM_PO_SENT/TREASURY.PO_SENT/BUDGETING.PO_COMMITMENT, and do not
            // write a second SEND audit entry for the same version.
            SupplierPoDispatch currentDispatch = supplierPoDispatchService.findCurrent(id).orElse(null);
            if (currentDispatch == null || !Objects.equals(currentDispatch.getPurchaseOrderVersion(), po.getVersion())) {
                throw new BusinessException("Only APPROVED purchase order can be sent");
            }
            return toResponse(po);
        }

        UUID contractId = po.getContract() != null ? po.getContract().getId() : po.getContractId();
        UUID supplierId = po.getSupplier() != null ? po.getSupplier().getId() : po.getSupplierId();
        List<PurchaseOrderItem> items = itemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), id);
        boolean worksPurchaseOrder = isWorksPurchaseOrder(po, items);
        if (worksPurchaseOrder) {
            UUID resolvedContractId = contractService.requireWorksContract(contractId, supplierId, currency(po));
            if (!Objects.equals(po.getContractId(), resolvedContractId)) {
                po.setContractId(resolvedContractId);
                contractId = resolvedContractId;
            }
        }
        BigDecimal materialAmount = worksPurchaseOrder ? BigDecimal.ZERO : materialAmount(id);
        if (materialAmount.signum() > 0) {
            contractService.consumeLimit(contractId, materialAmount, false, "PurchaseOrder " + po.getCode());
        }
        // Dispatch/snapshot must exist before the PO is allowed to flip to SENT --
        // both happen in this same transaction so a dispatch failure rolls back status too.
        serviceOrderLinkService.ensureForPurchaseOrder(po, "ENSURE_ON_PURCHASE_ORDER_SEND");
        supplierPoDispatchService.createOrReuseDispatch(id);
        recordBudgetCommitmentsOnSend(po);
        po.setStatus(PurchaseOrderStatus.SENT.name());
        integrationEventService.emit("MM_PO_SENT", "PurchaseOrder", po.getId(), Map.ofEntries(
            Map.entry("purchaseOrderId", po.getId()),
            Map.entry("purchaseOrderCode", po.getCode()),
            Map.entry("purchaseOrderName", po.getName() == null ? "" : po.getName()),
            Map.entry("supplierId", po.getSupplierId()),
            Map.entry("supplierName", po.getSupplier() != null ? po.getSupplier().getName() : ""),
            Map.entry("contractId", po.getContractId() == null ? "" : po.getContractId()),
            Map.entry("amount", amount(po)),
            Map.entry("currency", currency(po)),
            Map.entry("orderDate", po.getOrderDate() == null ? "" : po.getOrderDate()),
            Map.entry("expectedDeliveryDate", po.getExpectedDeliveryDate() == null ? "" : po.getExpectedDeliveryDate()),
            Map.entry("purchaseRequestId", po.getPurchaseRequestId() == null ? "" : po.getPurchaseRequestId()),
            Map.entry("status", po.getStatus())
        ));
        if (pilotCapabilityPolicy.enabled(PilotCapability.MM_FINANCE_EXECUTION)) {
            integrationEventService.emit("TREASURY.PO_SENT", "TREASURY", po.getId(), Map.ofEntries(
                Map.entry("purchaseOrderId", po.getId()),
                Map.entry("purchaseOrderCode", po.getCode()),
                Map.entry("supplierId", po.getSupplierId()),
                Map.entry("supplierName", po.getSupplier() != null ? po.getSupplier().getName() : ""),
                Map.entry("amount", amount(po)),
                Map.entry("currency", currency(po)),
                Map.entry("orderDate", po.getOrderDate() == null ? "" : po.getOrderDate()),
                Map.entry("expectedDeliveryDate", po.getExpectedDeliveryDate() == null ? "" : po.getExpectedDeliveryDate())
            ));
        }
        integrationEventService.emit("BUDGETING.PO_COMMITMENT", "BUDGETING", po.getId(), Map.ofEntries(
            Map.entry("purchaseOrderId", po.getId()),
            Map.entry("purchaseOrderCode", po.getCode()),
            Map.entry("supplierId", po.getSupplierId()),
            Map.entry("supplierName", po.getSupplier() != null ? po.getSupplier().getName() : ""),
            Map.entry("amount", amount(po)),
            Map.entry("currency", currency(po)),
            Map.entry("purchaseRequestId", po.getPurchaseRequestId() == null ? "" : po.getPurchaseRequestId()),
            Map.entry("contractId", po.getContractId() == null ? "" : po.getContractId())
        ));
        auditService.log("PurchaseOrder", id, "SEND", po.getCode(), null, null);
        return toResponse(repository.save(po));
    }
    @Transactional
    public PurchaseOrderResponse receive(UUID id, UUID warehouseId, UUID storageLocationId) {
        return receive(id, new ReceivePoRequest(warehouseId, storageLocationId, null, List.of()));
    }

    @Transactional
    public PurchaseOrderResponse receiveIdempotent(UUID id, ReceivePoRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for purchase order receipt");
        }
        if (request == null || request.version() == null) {
            throw new BadRequestException("version is required for purchase order receipt");
        }
        repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Purchase order not found"));
        return idempotencyService.execute(
            "PURCHASE_ORDER_RECEIVE",
            id,
            idempotencyKey,
            request,
            PurchaseOrderResponse.class,
            () -> {
                PurchaseOrder current = findEntity(id);
                if (!Objects.equals(request.version(), current.getVersion())) {
                    throw new ConflictException(
                        "Purchase order version conflict: expected=" + request.version() + ", actual=" + current.getVersion()
                    );
                }
                return receiveInternal(id, request, idempotencyKey.trim());
            }
        );
    }

    @Transactional
    public PurchaseOrderResponse receive(UUID id, ReceivePoRequest request) {
        return receiveInternal(id, request, null);
    }

    private PurchaseOrderResponse receiveInternal(UUID id, ReceivePoRequest request, String idempotencyKey) {
        repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Purchase order not found"));
        ReceivePoRequest effectiveRequest = request == null ? new ReceivePoRequest(null, null, null, List.of()) : request;
        PurchaseOrder po = findEntity(id);
        // UX-LIVE-04: same RECEIVE eligibility the goods-income workspace advertises.
        goodsIncomeReceiveActionPolicy.requireReceiveAllowed(po);
        List<PurchaseOrderItem> existingItems = itemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), id);
        if (isWorksPurchaseOrder(po, existingItems)) {
            throw receiptError(
                po,
                "Warehouse receipt is not applicable for WORKS purchase orders",
                "goodsIncome.po.notGoods",
                po.getId());
        }
        if (!PurchaseOrderStatus.SENT.name().equals(po.getStatus()) && !PurchaseOrderStatus.PARTIALLY_RECEIVED.name().equals(po.getStatus())) {
            throw receiptError(
                po,
                "Only SENT or PARTIALLY_RECEIVED purchase order can be received",
                "goodsIncome.action.receiveNotAllowed",
                po.getStatus());
        }
        UUID warehouseId = effectiveRequest.warehouseId();
        if (warehouseId == null) {
            throw receiptError(
                po,
                "Warehouse is required for purchase order receipt",
                "goodsIncome.receipt.warehouseRequired");
        }
        UUID defaultStorageLocationId = effectiveRequest.storageLocationId();
        UUID defaultStorageBinId = effectiveRequest.storageBinId();
        validateReceiptDestination(po, warehouseId, defaultStorageLocationId, defaultStorageBinId, false);
        var items = itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), id);
        if (items.isEmpty()) {
            throw receiptError(po, "Purchase order has no items", null);
        }
        if (items.stream().noneMatch(this::isTmbMaterialLine)) {
            items.stream()
                .filter(this::isMaterialLine)
                .filter(item -> item.getMaterialTypeSnapshot() == MaterialType.FIXED_ASSET)
                .findFirst()
                .ifPresent(item -> requireTmbReceiptLine(po, item));
            throw receiptError(po, "Warehouse receipt is only allowed for TMB purchase order lines", "TMB_STOCK_ONLY");
        }
        // UX-LIVE-01-1: Check accreditation eligibility - same as workspace blocker
        validateReceiptAccreditation(po, items);
        AdvanceShippingNotice asn = validateRequiredReceiptAsn(po, effectiveRequest.asnId());
        Map<UUID, PurchaseOrderItem> itemsById = items.stream().collect(java.util.stream.Collectors.toMap(PurchaseOrderItem::getId, item -> item));
        List<ReceivePoLineRequest> lines = effectiveRequest.lines() == null || effectiveRequest.lines().isEmpty()
            ? items.stream()
                .filter(this::isTmbMaterialLine)
                .filter(item -> openQuantity(item).signum() > 0)
                .map(item -> new ReceivePoLineRequest(item.getId(), openQuantity(item), BigDecimal.ZERO, BigDecimal.ZERO, null, null, defaultStorageLocationId, null, defaultStorageBinId))
                .toList()
            : effectiveRequest.lines();
        if (lines.isEmpty()) {
            throw receiptError(po, "Purchase order is already fully received", null);
        }
        validateReceiptEvidence(po, lines, effectiveRequest.evidenceAttachmentIds());
        Map<UUID, List<AdvanceShippingNoticeItem>> asnItemsByPurchaseOrderItem = asnItemsByPurchaseOrderItem(asn, items);
        boolean hasReceiptWork = false;
        BigDecimal acceptedAmount = BigDecimal.ZERO;
        BigDecimal acceptedBaseAmount = BigDecimal.ZERO;
        BigDecimal acceptedQuantity = BigDecimal.ZERO;
        BigDecimal physicalReceivedQuantity = BigDecimal.ZERO;
        BigDecimal rejectedQuantity = BigDecimal.ZERO;
        BigDecimal damagedQuantity = BigDecimal.ZERO;
        List<ReceiptMovementOutcome> movementOutcomes = new ArrayList<>();
        List<ReceiptQualityOutcome> qualityOutcomes = new ArrayList<>();
        List<ReceiptLinePostingDraft> receiptLineDrafts = new ArrayList<>();
        for (ReceivePoLineRequest line : lines) {
            if (line.purchaseOrderItemId() == null) {
                throw new BusinessException("purchaseOrderItemId is required for each receipt line");
            }
            PurchaseOrderItem item = itemsById.get(line.purchaseOrderItemId());
            if (item == null) {
                throw new BusinessException("Purchase order item does not belong to purchase order: " + line.purchaseOrderItemId());
            }
            if (!isMaterialLine(item)) {
                throw new BusinessException("Warehouse receipt is not allowed for SERVICE line " + item.getCode());
            }
            requireTmbReceiptLine(po, item);
            boolean hasExplicitAcceptedQuantity = line.acceptedQuantity() != null;
            BigDecimal acceptedInput = hasExplicitAcceptedQuantity
                ? nvl(line.acceptedQuantity())
                : nvl(line.receivedQuantity());
            BigDecimal rejected = nvl(line.rejectedQuantity());
            BigDecimal damaged = nvl(line.damagedQuantity());
            BigDecimal processed = acceptedInput.add(rejected).add(damaged);
            // New clients send outcome quantities explicitly. Keep receivedQuantity as a
            // compatibility path because legacy clients used it as the accepted candidate.
            BigDecimal physicalReceived = processed;
            String shortageReason = trimToNull(line.shortageReason());
            if (processed.signum() <= 0 && shortageReason == null) {
                throw new BusinessException("Receipt line must include accepted, rejected, damaged quantity or shortage reason");
            }
            if (acceptedInput.signum() < 0 || rejected.signum() < 0 || damaged.signum() < 0) {
                throw new BusinessException("Receipt quantities cannot be negative");
            }
            BigDecimal remaining = openQuantity(item);
            if (processed.compareTo(remaining) > 0) {
                throw new BusinessException("Receipt quantity exceeds open PO item quantity for " + item.getCode());
            }
            reserveAsnReceiptQuantity(asnItemsByPurchaseOrderItem, item, processed);
            UUID lineStorageLocationId = line.storageLocationId() == null ? defaultStorageLocationId : line.storageLocationId();
            UUID lineStorageBinId = line.storageBinId() == null ? defaultStorageBinId : line.storageBinId();
            validateReceiptDestination(po, warehouseId, lineStorageLocationId, lineStorageBinId, true);
            assertReceiptAccreditation(po, item);
            Material receiptMaterial = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), item.getMaterialId())
                .orElseThrow(() -> new BusinessException("Material not found"));
            StockUomPolicy.purchasing(receiptMaterial, item.getUnit(), processed);
            StockUomPolicy.quantity(receiptMaterial, acceptedInput, true);
            StockUomPolicy.quantity(receiptMaterial, rejected, true);
            StockUomPolicy.quantity(receiptMaterial, damaged, true);
            boolean inspectionRequired = acceptedInput.signum() > 0
                && (receiptMaterial.isIncomingInspectionRequired() || rejected.signum() > 0 || damaged.signum() > 0 || shortageReason != null);
            String qualityStatus = inspectionRequired
                ? "PENDING_INSPECTION"
                : resolveQualityStatus(rejected, damaged, physicalReceived, shortageReason);
            BigDecimal accepted = acceptedInput; // Valued candidate stock; usability is determined by inspection.
            BigDecimal fulfilled = inspectionRequired ? BigDecimal.ZERO : accepted;
            BatchTrackingPolicy batchPolicy = batchTrackingPolicy(item);
            SerialTrackingPolicy serialPolicy = serialTrackingPolicy(item);
            List<ReceivePoBatchRequest> receiptBatches = receiptBatches(line, batchPolicy, accepted);
            validateBatchReceipt(po, item, batchPolicy, serialPolicy, receiptBatches, accepted);
            UUID stockMovementId = null;
            UUID stockBatchId = null;
            List<PostedBatchReceipt> postedBatchReceipts = new ArrayList<>();
            if (accepted.signum() > 0) {
                if (receiptBatches.isEmpty()) {
                    var postedReceipt = stockPostingPort.postAcceptedReceipt(new AcceptedReceipt(
                        receiptMovementCode(po, item),
                        "Goods receipt for " + po.getCode(),
                        item.getMaterialId(),
                        warehouseId,
                        lineStorageLocationId,
                        lineStorageBinId,
                        trimToNull(line.batchNumber()),
                        accepted,
                        receiptUnitCost(po, item),
                        po.getId(),
                        currency(po),
                        effectiveExchangeRate(po),
                        item.getUnitPrice()
                    ));
                    stockMovementId = postedReceipt.stockMovementId();
                    stockBatchId = postedReceipt.stockBatchId();
                    supplierEvaluationService.evaluateReceipt(
                        po, item, postedReceipt.stockMovementId(), accepted, postedReceipt.movementDate());
                    movementOutcomes.add(new ReceiptMovementOutcome(
                        postedReceipt.stockMovementId(),
                        item.getId(),
                        item.getMaterialId(),
                        accepted,
                        postedReceipt.movementDate()
                    ));
                } else {
                    for (ReceivePoBatchRequest batch : receiptBatches) {
                        BigDecimal batchQuantity = nvl(batch.quantity());
                        if (batchQuantity.signum() <= 0) {
                            continue;
                        }
                        var postedReceipt = stockPostingPort.postAcceptedReceipt(new AcceptedReceipt(
                            receiptMovementCode(po, item),
                            "Goods receipt for " + po.getCode(),
                            item.getMaterialId(),
                            warehouseId,
                            lineStorageLocationId,
                            lineStorageBinId,
                            trimToNull(batch.manufacturerBatchNumber()),
                            batchQuantity,
                            receiptUnitCost(po, item),
                            po.getId(),
                            currency(po),
                            effectiveExchangeRate(po),
                            item.getUnitPrice()
                        ));
                        if (stockMovementId == null) {
                            stockMovementId = postedReceipt.stockMovementId();
                            stockBatchId = postedReceipt.stockBatchId();
                        }
                        postedBatchReceipts.add(new PostedBatchReceipt(postedReceipt.stockMovementId(), postedReceipt.stockBatchId()));
                        supplierEvaluationService.evaluateReceipt(
                            po, item, postedReceipt.stockMovementId(), batchQuantity, postedReceipt.movementDate());
                        movementOutcomes.add(new ReceiptMovementOutcome(
                            postedReceipt.stockMovementId(),
                            item.getId(),
                            item.getMaterialId(),
                            batchQuantity,
                            postedReceipt.movementDate()
                        ));
                    }
                }
                acceptedAmount = acceptedAmount.add(accepted.multiply(nvl(item.getUnitPrice())));
                acceptedBaseAmount = acceptedBaseAmount.add(accepted.multiply(receiptUnitCost(po, item)));
            }
            physicalReceivedQuantity = physicalReceivedQuantity.add(physicalReceived);
            acceptedQuantity = acceptedQuantity.add(accepted);
            rejectedQuantity = rejectedQuantity.add(rejected);
            damagedQuantity = damagedQuantity.add(damaged);
            item.setReceivedQuantity(nvl(item.getReceivedQuantity()).add(physicalReceived));
            item.setAcceptedQuantity(nvl(item.getAcceptedQuantity()).add(fulfilled));
            item.setRejectedQuantity(nvl(item.getRejectedQuantity()).add(rejected));
            item.setDamagedQuantity(nvl(item.getDamagedQuantity()).add(damaged));
            item.setShortageReason(shortageReason);
            item.setReceiptBatchNumber(receiptBatches.size() == 1
                ? trimToNull(receiptBatches.get(0).manufacturerBatchNumber())
                : trimToNull(line.batchNumber()));
            item.setReceiptWarehouseId(warehouseId);
            item.setReceiptStorageLocationId(lineStorageLocationId);
            item.setQualityStatus(inspectionRequired || qualityInspectionService.hasPendingInspection(po.getId(), item.getId()) ? "PENDING_INSPECTION" : qualityStatus);
            item.setAccreditationWarning(null);
            item.setStatus(receiptLineStatus(item));
            itemRepository.save(item);
            UUID inspectionMovementId = postedBatchReceipts.size() <= 1 ? stockMovementId : null;
            if (receiptBatches.isEmpty()) {
                ReceiptQualityOutcome outcome = recordPendingInspection(po, item, warehouseId, lineStorageLocationId,
                    qualityStatus, accepted, inspectionMovementId);
                if (outcome != null) qualityOutcomes.add(outcome);
            } else {
                for (int batchIndex = 0; batchIndex < postedBatchReceipts.size(); batchIndex++) {
                    ReceiptQualityOutcome outcome = recordPendingInspection(po, item, warehouseId, lineStorageLocationId,
                        qualityStatus, receiptBatches.get(batchIndex).quantity(), postedBatchReceipts.get(batchIndex).stockMovementId());
                    if (outcome != null) qualityOutcomes.add(outcome);
                }
            }
            BigDecimal shortageQuantity = shortageReason == null
                ? BigDecimal.ZERO
                : remaining.subtract(processed).max(BigDecimal.ZERO);
            receiptLineDrafts.add(new ReceiptLinePostingDraft(
                item.getId(),
                item.getMaterialId(),
                materialGroupId(po, item),
                trimToNull(item.getUnit()),
                warehouseId,
                lineStorageLocationId,
                lineStorageBinId,
                trimToNull(line.batchNumber()),
                remaining,
                physicalReceived,
                accepted,
                rejected,
                damaged,
                BigDecimal.ZERO,
                shortageQuantity,
                shortageReason,
                qualityStatus,
                stockMovementId,
                stockBatchId,
                trackingMode(item),
                receiptUnitDrafts(line),
                "ELIGIBLE",
                null,
                null,
                receiptBatchDrafts(receiptBatches, postedBatchReceipts, serialPolicy)
            ));
            hasReceiptWork = true;
        }
        if (!hasReceiptWork) {
            throw receiptError(po, "No receipt work was posted", null);
        }
        boolean allMaterialProcessed = items.stream().filter(this::isTmbMaterialLine).allMatch(item -> openQuantity(item).signum() <= 0);
        boolean allServiceAccepted = items.stream().filter(this::isServiceLine).allMatch(this::hasAcceptedServiceAct);
        po.setStatus(allMaterialProcessed && allServiceAccepted ? PurchaseOrderStatus.RECEIVED.name() : PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        repository.save(po);
        ReceiptPostingResult receiptPosting = warehouseReceiptPostingService.record(new ReceiptPostingDraft(
            po.getId(),
            supplierId(po),
            asn == null ? null : asn.getId(),
            idempotencyKey,
            currency(po),
            effectiveExchangeRate(po),
            acceptedAmount,
            acceptedBaseAmount,
            effectiveRequest.evidenceAttachmentIds(),
            List.copyOf(receiptLineDrafts)
        ));
        if (effectiveRequest.packaging() != null && !effectiveRequest.packaging().isEmpty()) {
            if (returnablePackagingService == null)
                throw new BusinessException("Returnable packaging service is unavailable");
            returnablePackagingService.recordReceipt(receiptPosting.receiptId(), supplierId(po), effectiveRequest.packaging());
        }
        fakturaEvidence.attach(po.getContractId(), FakturaReceiptEvidenceService.ReceiptKind.GOODS_RECEIPT,
            receiptPosting.receiptId(), java.time.LocalDate.now(), effectiveRequest.fakturaPowerOfAttorneyId(),
            effectiveRequest.fakturaInvoiceId(), effectiveRequest.missingFakturaReason());
        int deliveryScore = po.getExpectedDeliveryDate() != null && LocalDateTime.now().isAfter(po.getExpectedDeliveryDate().atStartOfDay()) ? 70 : 100;
        UUID supplierId = po.getSupplier() != null ? po.getSupplier().getId() : po.getSupplierId();
        supplierEvaluationService.evaluateDelivery(supplierId, po.getId(), deliveryScore);
        if (asn != null) {
            String nextAsnStatus = asnReceiptStatus(asnItemsByPurchaseOrderItem);
            asn.setStatus(nextAsnStatus);
            if ("RECEIVED".equals(nextAsnStatus)) {
                asn.setReceivedAt(LocalDateTime.now());
            }
            asnRepository.save(asn);
            applyAsnReceiptToSupplierCommitmentSchedules(asn.getId(), physicalReceivedQuantity, acceptedAmount);
        }
        List<ReceiptIntegrationOutcome> integrationOutcomes = new ArrayList<>();
        integrationOutcomes.add(toIntegrationOutcome(integrationEventService.emit(
            "MM_GOODS_RECEIVED",
            "PurchaseOrder",
            po.getId(),
            Map.ofEntries(
                Map.entry("purchaseOrderId", po.getId()),
                Map.entry("purchaseOrderCode", po.getCode()),
                Map.entry("purchaseOrderName", po.getName() == null ? "" : po.getName()),
                Map.entry("supplierId", po.getSupplierId()),
                Map.entry("supplierName", po.getSupplier() != null ? po.getSupplier().getName() : ""),
                Map.entry("asnId", asn == null ? "" : asn.getId()),
                Map.entry("asnCode", asn == null || asn.getCode() == null ? "" : asn.getCode()),
                Map.entry("status", po.getStatus()),
                Map.entry("physicalReceivedQuantity", physicalReceivedQuantity),
                Map.entry("acceptedAmount", acceptedAmount),
                Map.entry("acceptedQuantity", acceptedQuantity),
                Map.entry("currency", currency(po)),
                Map.entry("receiptId", receiptPosting.receiptId()),
                Map.entry("warehouseId", effectiveRequest.warehouseId()),
                Map.entry("deliveryScore", deliveryScore)
            )
        )));
        if (acceptedAmount.signum() > 0) {
            integrationOutcomes.add(toIntegrationOutcome(integrationEventService.emit(
                "MM_ACCRUAL_REQUIRED",
                "PurchaseOrder",
                po.getId(),
                Map.ofEntries(
                    Map.entry("target", "FI,TREASURY"),
                    Map.entry("purchaseOrderId", po.getId()),
                    Map.entry("purchaseOrderCode", po.getCode()),
                    Map.entry("supplierId", po.getSupplierId()),
                    Map.entry("supplierName", po.getSupplier() != null ? po.getSupplier().getName() : ""),
                    Map.entry("goodsReceiptId", receiptPosting.receiptId()),
                    Map.entry("amount", acceptedAmount),
                    Map.entry("currency", currency(po)),
                    Map.entry("baseAmount", acceptedBaseAmount),
                    Map.entry("baseCurrency", "UZS")
                )
            )));
            integrationOutcomes.add(toIntegrationOutcome(integrationEventService.emit(
                "FI.STOCK_RECEIPT_POSTED",
                "FI",
                po.getId(),
                Map.of(
                    "purchaseOrderId", po.getId(),
                    "goodsReceiptId", receiptPosting.receiptId(),
                    "amount", acceptedBaseAmount,
                    "currency", "UZS",
                    "sourceAmount", acceptedAmount,
                    "sourceCurrency", currency(po),
                    "exchangeRate", effectiveExchangeRate(po)
                )
            )));
            integrationOutcomes.add(toIntegrationOutcome(integrationEventService.emit(
                "MM.INVOICE_REQUIRED",
                "PurchaseOrder",
                po.getId(),
                Map.of(
                    "purchaseOrderId", po.getId(),
                    "goodsReceiptId", receiptPosting.receiptId(),
                    "amount", acceptedAmount,
                    "currency", currency(po)
                )
            )));
            payablesNotificationService.notifyInvoiceRequired(po, acceptedAmount, currency(po));
        }
        auditService.log("PurchaseOrder", id, "RECEIVE", warehouseId.toString(), null, null);
        PurchaseOrderResponse response = toResponse(po);
        response.setReceiptOutcome(new ReceiptOutcome(
            po.getId(),
            po.getStatus(),
            acceptedQuantity,
            rejectedQuantity,
            damagedQuantity,
            rejectedQuantity.add(damagedQuantity).add(qualityOutcomes.stream().map(ReceiptQualityOutcome::quantity).reduce(BigDecimal.ZERO, BigDecimal::add)),
            List.copyOf(movementOutcomes),
            List.copyOf(integrationOutcomes),
            List.copyOf(qualityOutcomes),
            receiptPosting.receiptId(),
            receiptPosting.receiptCode(),
            receiptPosting.materialUnitsCreated(),
            receiptPosting.receiptUnitsRecorded(),
            LocalDateTime.now()
        ));
        return response;
    }

    private TrackingMode trackingMode(PurchaseOrderItem item) {
        BatchTrackingPolicy batchPolicy = batchTrackingPolicy(item);
        SerialTrackingPolicy serialPolicy = serialTrackingPolicy(item);
        if (serialPolicy == SerialTrackingPolicy.REQUIRED || batchPolicy == BatchTrackingPolicy.REQUIRED) {
            return TrackingMode.fromPolicies(batchPolicy, serialPolicy);
        }
        if (item.getTrackingModeSnapshot() != null) {
            return item.getTrackingModeSnapshot();
        }
        if (item.getMaterial() != null && item.getMaterial().getTrackingMode() != null) {
            return item.getMaterial().getTrackingMode();
        }
        return TrackingMode.QUANTITY;
    }

    private BatchTrackingPolicy batchTrackingPolicy(PurchaseOrderItem item) {
        if (item.getBatchTrackingSnapshot() != null) {
            return item.getBatchTrackingSnapshot();
        }
        if (item.getTrackingModeSnapshot() != null) {
            return TrackingMode.batchTrackingPolicy(item.getTrackingModeSnapshot());
        }
        if (item.getMaterial() != null && item.getMaterial().getBatchTrackingPolicy() != null) {
            return item.getMaterial().getBatchTrackingPolicy();
        }
        return item.getMaterial() == null ? BatchTrackingPolicy.NONE : TrackingMode.batchTrackingPolicy(item.getMaterial().getTrackingMode());
    }

    private SerialTrackingPolicy serialTrackingPolicy(PurchaseOrderItem item) {
        if (item.getSerialTrackingSnapshot() != null) {
            return item.getSerialTrackingSnapshot();
        }
        if (item.getTrackingModeSnapshot() != null) {
            return TrackingMode.serialTrackingPolicy(item.getTrackingModeSnapshot());
        }
        if (item.getMaterial() != null && item.getMaterial().getSerialTrackingPolicy() != null) {
            return item.getMaterial().getSerialTrackingPolicy();
        }
        return item.getMaterial() == null ? SerialTrackingPolicy.NONE : TrackingMode.serialTrackingPolicy(item.getMaterial().getTrackingMode());
    }

    private List<ReceivePoBatchRequest> receiptBatches(
        ReceivePoLineRequest line,
        BatchTrackingPolicy batchPolicy,
        BigDecimal accepted
    ) {
        if (line.batches() != null && !line.batches().isEmpty()) {
            return line.batches();
        }
        String legacyBatchNumber = trimToNull(line.batchNumber());
        if (legacyBatchNumber != null && batchPolicy != BatchTrackingPolicy.NONE) {
            return List.of(new ReceivePoBatchRequest(
                legacyBatchNumber,
                legacyBatchNumber,
                null,
                null,
                accepted,
                line.units()
            ));
        }
        return List.of();
    }

    private void validateBatchReceipt(
        PurchaseOrder po,
        PurchaseOrderItem item,
        BatchTrackingPolicy batchPolicy,
        SerialTrackingPolicy serialPolicy,
        List<ReceivePoBatchRequest> batches,
        BigDecimal accepted
    ) {
        if (batchPolicy == BatchTrackingPolicy.NONE && !batches.isEmpty()) {
            throw receiptError(po, "BATCH_NOT_ALLOWED", "goodsIncome.receipt.batchNotAllowed", item.getCode());
        }
        if (batchPolicy == BatchTrackingPolicy.REQUIRED && accepted.signum() > 0 && batches.isEmpty()) {
            throw receiptError(po, "BATCH_REQUIRED", "goodsIncome.receipt.batchRequired", item.getCode());
        }
        if (batchPolicy == BatchTrackingPolicy.REQUIRED && batches.stream().anyMatch(batch -> trimToNull(batch.manufacturerBatchNumber()) == null)) {
            throw receiptError(po, "BATCH_NUMBER_REQUIRED", "goodsIncome.receipt.batchNumberRequired", item.getCode());
        }
        BigDecimal batchAccepted = batches.stream()
            .map(ReceivePoBatchRequest::quantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!batches.isEmpty() && batchAccepted.compareTo(accepted) != 0) {
            throw receiptError(po, "BATCH_QUANTITY_MISMATCH", "goodsIncome.receipt.batchQuantityMismatch", item.getCode());
        }
        if (serialPolicy == SerialTrackingPolicy.REQUIRED && !batches.isEmpty()) {
            for (ReceivePoBatchRequest batch : batches) {
                BigDecimal acceptedUnits = BigDecimal.valueOf(batch.units().stream()
                    .filter(unit -> "ACCEPTED".equals(normalizeDecision(unit.qualityDecision())))
                    .count());
                if (acceptedUnits.compareTo(nvl(batch.quantity())) != 0) {
                    throw receiptError(po, "SERIAL_BATCH_UNIT_COUNT_MISMATCH", "goodsIncome.receipt.serialBatchCountMismatch", item.getCode());
                }
            }
        }
    }

    private List<ReceiptBatchPostingDraft> receiptBatchDrafts(
        List<ReceivePoBatchRequest> batches,
        List<PostedBatchReceipt> postedBatchReceipts,
        SerialTrackingPolicy serialPolicy
    ) {
        if (batches.isEmpty()) {
            return List.of();
        }
        java.util.ArrayDeque<PostedBatchReceipt> posted = new java.util.ArrayDeque<>(postedBatchReceipts);
        return batches.stream()
            .map(batch -> {
                PostedBatchReceipt postedBatch = nvl(batch.quantity()).signum() > 0 && !posted.isEmpty()
                    ? posted.removeFirst()
                    : null;
                List<ReceiptUnitPostingDraft> units = serialPolicy == SerialTrackingPolicy.REQUIRED
                    ? batch.units().stream()
                        .map(unit -> new ReceiptUnitPostingDraft(
                            trimToNull(unit.manufacturerSerialNumber()),
                            trimToNull(unit.condition()),
                            trimToNull(unit.qualityDecision()),
                            trimToNull(unit.notes())
                        ))
                        .toList()
                    : List.of();
                BigDecimal rejectedUnits = serialPolicy == SerialTrackingPolicy.REQUIRED
                    ? BigDecimal.valueOf(batch.units().stream()
                        .filter(unit -> "REJECTED".equals(normalizeDecision(unit.qualityDecision())))
                        .count())
                    : BigDecimal.ZERO;
                return new ReceiptBatchPostingDraft(
                    trimToNull(batch.batchKey()),
                    postedBatch == null ? null : postedBatch.stockMovementId(),
                    postedBatch == null ? null : postedBatch.stockBatchId(),
                    trimToNull(batch.manufacturerBatchNumber()),
                    batch.manufacturedOn(),
                    batch.expiresOn(),
                    nvl(batch.quantity()),
                    rejectedUnits,
                    BigDecimal.ZERO,
                    units
                );
            })
            .toList();
    }

    private List<ReceiptUnitPostingDraft> receiptUnitDrafts(ReceivePoLineRequest line) {
        if (line.units() == null || line.units().isEmpty()) {
            return List.of();
        }
        return line.units().stream()
            .map(unit -> new ReceiptUnitPostingDraft(
                trimToNull(unit.manufacturerSerialNumber()),
                trimToNull(unit.condition()),
                trimToNull(unit.qualityDecision()),
                trimToNull(unit.notes())
            ))
            .toList();
    }

    private String normalizeDecision(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? "ACCEPTED" : normalized.toUpperCase(Locale.ROOT);
    }

    private record PostedBatchReceipt(UUID stockMovementId, UUID stockBatchId) {
    }

    private ReceiptIntegrationOutcome toIntegrationOutcome(com.enterprise.mm.entity.IntegrationEvent event) {
        return new ReceiptIntegrationOutcome(event.getId(), event.getEventType(), event.getStatus());
    }

    private void applyAsnReceiptToSupplierCommitmentSchedules(
        UUID asnId,
        BigDecimal physicalQuantity,
        BigDecimal acceptedAmount
    ) {
        if (physicalQuantity == null || physicalQuantity.signum() <= 0) {
            return;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        BigDecimal remainingPhysical = physicalQuantity;
        for (var asnItem : asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(tenantId, companyId, asnId)) {
            UUID commitmentId = asnItem.getSupplierPoCommitmentId();
            if (commitmentId == null || remainingPhysical.signum() <= 0) {
                continue;
            }
            var schedule = deliveryScheduleRepository
                .findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(tenantId, companyId, commitmentId)
                .orElse(null);
            if (schedule == null) {
                continue;
            }
            BigDecimal planned = nvl(schedule.getPlannedQuantity());
            BigDecimal currentReceived = nvl(schedule.getReceivedQuantity());
            BigDecimal stillOpen = planned.subtract(currentReceived).max(BigDecimal.ZERO);
            if (stillOpen.signum() <= 0) {
                continue;
            }
            // The ASN line counter already includes candidates, rejects and damage.
            // Derive this commitment's cumulative physical receipt, not a PO-wide accepted allocation.
            BigDecimal receivedForCommitment = asnItemRepository
                .findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(tenantId, companyId, commitmentId)
                .stream().map(line -> nvl(line.getReceivedQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal appliedQuantity = receivedForCommitment.subtract(currentReceived).max(BigDecimal.ZERO)
                .min(stillOpen).min(remainingPhysical);
            if (appliedQuantity.signum() <= 0) {
                continue;
            }
            BigDecimal appliedAmount = physicalQuantity.signum() == 0
                ? BigDecimal.ZERO
                : nvl(acceptedAmount).multiply(appliedQuantity).divide(physicalQuantity, 4, java.math.RoundingMode.HALF_UP);
            BigDecimal nextReceived = currentReceived.add(appliedQuantity);
            schedule.setReceivedQuantity(nextReceived);
            schedule.setReceivedAmount(nvl(schedule.getReceivedAmount()).add(appliedAmount));
            schedule.setStatus(nextReceived.compareTo(planned) >= 0 ? "RECEIVED" : "PARTIALLY_RECEIVED");
            schedule.setControlledAt(LocalDateTime.now());
            deliveryScheduleRepository.save(schedule);
            remainingPhysical = remainingPhysical.subtract(appliedQuantity);
        }
    }

    private Map<UUID, List<AdvanceShippingNoticeItem>> asnItemsByPurchaseOrderItem(
        AdvanceShippingNotice asn,
        List<PurchaseOrderItem> purchaseOrderItems
    ) {
        Map<UUID, List<AdvanceShippingNoticeItem>> itemsByPurchaseOrderItem = new HashMap<>();
        if (asn == null) {
            return itemsByPurchaseOrderItem;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        for (AdvanceShippingNoticeItem asnItem : asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            tenantId, companyId, asn.getId())) {
            UUID purchaseOrderItemId = resolveAsnPurchaseOrderItemId(asnItem, purchaseOrderItems);
            if (purchaseOrderItemId == null) {
                continue;
            }
            itemsByPurchaseOrderItem.computeIfAbsent(purchaseOrderItemId, ignored -> new ArrayList<>()).add(asnItem);
        }
        return itemsByPurchaseOrderItem;
    }

    private UUID resolveAsnPurchaseOrderItemId(
        AdvanceShippingNoticeItem asnItem,
        List<PurchaseOrderItem> purchaseOrderItems
    ) {
        if (asnItem.getPurchaseOrderItemId() != null) {
            return asnItem.getPurchaseOrderItemId();
        }
        if (asnItem.getMaterialId() == null || purchaseOrderItems == null) {
            return null;
        }
        List<PurchaseOrderItem> matches = purchaseOrderItems.stream()
            .filter(this::isMaterialLine)
            .filter(item -> Objects.equals(item.getMaterialId(), asnItem.getMaterialId()))
            .toList();
        if (matches.size() != 1) {
            return null;
        }
        UUID purchaseOrderItemId = matches.get(0).getId();
        asnItem.setPurchaseOrderItemId(purchaseOrderItemId);
        asnItemRepository.save(asnItem);
        return purchaseOrderItemId;
    }

    private void reserveAsnReceiptQuantity(
        Map<UUID, List<AdvanceShippingNoticeItem>> asnItemsByPurchaseOrderItem,
        PurchaseOrderItem item,
        BigDecimal processedQuantity
    ) {
        if (processedQuantity.signum() <= 0) {
            return;
        }
        List<AdvanceShippingNoticeItem> asnItems = asnItemsByPurchaseOrderItem.getOrDefault(item.getId(), List.of());
        BigDecimal asnRemaining = asnItems.stream()
            .map(this::asnLineRemainingQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (processedQuantity.compareTo(asnRemaining) > 0) {
            throw new BusinessException("Receipt quantity exceeds ASN line quantity for " + item.getCode());
        }
        BigDecimal remainingToApply = processedQuantity;
        for (AdvanceShippingNoticeItem asnItem : asnItems) {
            if (remainingToApply.signum() <= 0) {
                break;
            }
            BigDecimal lineRemaining = asnLineRemainingQuantity(asnItem);
            if (lineRemaining.signum() <= 0) {
                continue;
            }
            BigDecimal applied = lineRemaining.min(remainingToApply);
            asnItem.setReceivedQuantity(nvl(asnItem.getReceivedQuantity()).add(applied));
            asnItemRepository.save(asnItem);
            remainingToApply = remainingToApply.subtract(applied);
        }
    }

    private BigDecimal asnLineRemainingQuantity(AdvanceShippingNoticeItem asnItem) {
        return nvl(asnItem.getQuantity())
            .subtract(nvl(asnItem.getReceivedQuantity()))
            .subtract(nvl(asnItem.getCancelledQuantity()))
            .max(BigDecimal.ZERO);
    }

    private String asnReceiptStatus(Map<UUID, List<AdvanceShippingNoticeItem>> asnItemsByPurchaseOrderItem) {
        List<AdvanceShippingNoticeItem> asnItems = asnItemsByPurchaseOrderItem.values().stream()
            .flatMap(List::stream)
            .toList();
        if (asnItems.isEmpty()) {
            return AdvanceShippingNoticeStatus.CONFIRMED.name();
        }
        boolean anyReceived = asnItems.stream().anyMatch(item -> nvl(item.getReceivedQuantity()).signum() > 0);
        boolean allReceived = asnItems.stream().allMatch(item -> asnLineRemainingQuantity(item).signum() <= 0);
        if (allReceived) {
            return "RECEIVED";
        }
        return anyReceived ? "PARTIALLY_RECEIVED" : AdvanceShippingNoticeStatus.CONFIRMED.name();
    }

    private void validateReceiptEvidence(
        PurchaseOrder purchaseOrder,
        List<ReceivePoLineRequest> lines,
        List<UUID> evidenceAttachmentIds
    ) {
        List<UUID> evidenceIds = evidenceAttachmentIds == null
            ? List.of()
            : evidenceAttachmentIds.stream().distinct().toList();
        boolean hasException = lines.stream().anyMatch(line ->
            nvl(line.rejectedQuantity()).signum() > 0
                || nvl(line.damagedQuantity()).signum() > 0
                || trimToNull(line.shortageReason()) != null
        );
        if (hasException && evidenceIds.isEmpty()) {
            throw new BusinessException("Receipt evidence is required for rejected, damaged or shortage lines");
        }
        for (UUID evidenceId : evidenceIds) {
            if (evidenceId == null) {
                throw new BusinessException("Receipt evidence attachment id is required");
            }
            var attachment = attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                evidenceId
            ).orElseThrow(() -> new BusinessException("Receipt evidence not found: " + evidenceId));
            if (!"PURCHASEORDER".equals(attachment.getEntityType())
                || !Objects.equals(purchaseOrder.getId(), attachment.getEntityId())) {
                throw new BusinessException("Receipt evidence does not belong to purchase order: " + evidenceId);
            }
        }
    }
    @Transactional
    public PurchaseOrderResponse close(UUID id) {
        PurchaseOrder po = findEntity(id);
        var items = itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), id);
        if (isWorksPurchaseOrder(po)) {
            CloseReadiness readiness = closeReadiness(po, items);
            if (!readiness.ready()) {
                throw new BusinessException(readiness.blockedReason());
            }
        } else {
            reconcileServicePurchaseOrderLines(po, items);
            CloseReadiness readiness = closeReadiness(po, items);
            if (!readiness.ready()) {
                throw new BusinessException(readiness.blockedReason());
            }
        }
        po.setStatus(PurchaseOrderStatus.CLOSED.name());
        auditService.log("PurchaseOrder", id, "CLOSE", po.getCode(), null, null);
        return toResponse(repository.save(po));
    }

    private CloseReadiness closeReadiness(PurchaseOrder po, List<PurchaseOrderItem> items) {
        if (po == null) {
            return CloseReadiness.blocked("Purchase order is required");
        }
        if (PurchaseOrderStatus.CLOSED.name().equals(po.getStatus())) {
            return CloseReadiness.blocked("Purchase order is already closed");
        }
        if (PurchaseOrderStatus.CANCELLED.name().equals(po.getStatus())) {
            return CloseReadiness.blocked("Cancelled purchase order cannot be closed");
        }
        try {
            assertNoUnresolvedSupplierCollaboration(po);
            if (isWorksPurchaseOrder(po, items)) {
                if (!PurchaseOrderStatus.COMPLETED.name().equals(po.getStatus())) {
                    return CloseReadiness.blocked("WORKS purchase order must be COMPLETED before close");
                }
                assertWorksReadyToClose(po);
                return CloseReadiness.allowed();
            }
            boolean hasMaterialLines = items.stream().anyMatch(this::isMaterialLine);
            boolean hasServiceLines = items.stream().anyMatch(this::isServiceLine);
            if (hasMaterialLines
                && items.stream().filter(this::isMaterialLine).anyMatch(item -> acceptedFulfillmentRemaining(item).signum() > 0)) {
                return CloseReadiness.blocked(
                    "Material purchase order lines require accepted fulfillment before close"
                );
            }
            if (hasServiceLines
                && items.stream().filter(this::isServiceLine).anyMatch(item -> !hasAcceptedServiceAct(item))) {
                return CloseReadiness.blocked(
                    "Service purchase order lines require accepted service act before close"
                );
            }
            if (!hasMaterialLines && !hasServiceLines) {
                return CloseReadiness.blocked("Purchase order has no lines");
            }
            if (!PurchaseOrderStatus.RECEIVED.name().equals(po.getStatus())
                && !PurchaseOrderStatus.PARTIALLY_RECEIVED.name().equals(po.getStatus())
                && !PurchaseOrderStatus.SENT.name().equals(po.getStatus())) {
                return CloseReadiness.blocked(
                    "Only SENT, PARTIALLY_RECEIVED or RECEIVED purchase order can be closed"
                );
            }
            return CloseReadiness.allowed();
        } catch (BusinessException exception) {
            return CloseReadiness.blocked(exception.getMessage());
        }
    }

    private record CloseReadiness(boolean ready, String blockedReason) {
        private static CloseReadiness allowed() {
            return new CloseReadiness(true, null);
        }

        private static CloseReadiness blocked(String blockedReason) {
            return new CloseReadiness(false, blockedReason);
        }
    }

    /** New close-blocking rule (Phase B, section 6): a current unresolved supplier
     * exception (REVISION_REQUESTED response with no subsequent accepted/superseded
     * response -- "current" already implies no subsequent response exists) or an open
     * confirmed commitment (CONFIRMED, not yet fulfilled by receipt) blocks close. No
     * existing waiver/bypass parameter exists on close() to check against, so this is a
     * hard block, matching every other close-blocking check in this method. */
    private void assertNoUnresolvedSupplierCollaboration(PurchaseOrder po) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        boolean unresolvedException = supplierPoResponseRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(tenantId, companyId, po.getId())
            .map(response -> "REVISION_REQUESTED".equals(response.getResponseStatus()))
            .orElse(false);
        if (unresolvedException) {
            throw new BusinessException(
                "Purchase order has an unresolved supplier revision request; resolve it before closing"
            );
        }
        List<PurchaseOrderItem> items = itemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, po.getId());
        boolean allMaterialFulfilled = items.stream().noneMatch(this::isMaterialLine)
            || items.stream().filter(this::isMaterialLine).allMatch(item -> acceptedFulfillmentRemaining(item).signum() <= 0);
        if (allMaterialFulfilled) {
            return;
        }
        // "CONFIRMED with no fulfilling receipt" -- checked against the projected
        // DeliverySchedule's actual received quantity, not just commitment.status,
        // since nothing in this phase transitions a commitment off CONFIRMED once the
        // warehouse receives it (receipt posting is a separate, pre-existing flow).
        boolean openConfirmedCommitments = supplierPoCommitmentRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                tenantId, companyId, po.getId(), List.of("CONFIRMED")
            )
            .stream()
            .anyMatch(commitment -> {
                if (commitment.getDeliveryScheduleId() == null) {
                    return true;
                }
                var schedule = deliveryScheduleRepository
                    .findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(tenantId, companyId, commitment.getId())
                    .orElse(null);
                if (schedule == null) {
                    return true;
                }
                BigDecimal planned = nvl(schedule.getPlannedQuantity());
                BigDecimal received = nvl(schedule.getReceivedQuantity());
                return received.compareTo(planned) < 0;
            });
        if (openConfirmedCommitments) {
            throw new BusinessException(
                "Purchase order has confirmed supplier commitments that are not yet fulfilled; receive them before closing"
            );
        }
    }

    private void assertWorksReadyToClose(PurchaseOrder po) {
        String tenantId = TenantContext.currentTenant();
        List<PurchaseOrderItem> workItems = itemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, po.getId())
            .stream()
            .filter(item -> item.getLineType() == ProcurementLineType.WORK)
            .toList();
        if (workItems.isEmpty()) {
            throw new BusinessException("WORKS purchase order has no work lines");
        }
        for (PurchaseOrderItem item : workItems) {
            BigDecimal orderedQuantity = nvl(item.getQuantity());
            BigDecimal paidQuantity = nvl(workCompletionActRepository.cumulativeQuantity(
                tenantId,
                item.getId(),
                List.of("PAID"),
                null
            ));
            BigDecimal orderedAmount = orderedQuantity.multiply(nvl(item.getUnitPrice()));
            BigDecimal paidAmount = nvl(workCompletionActRepository.cumulativeAmount(
                tenantId,
                item.getId(),
                List.of("PAID"),
                null
            ));
            if (orderedQuantity.signum() <= 0
                || paidQuantity.compareTo(orderedQuantity) < 0
                || orderedAmount.signum() <= 0
                || paidAmount.compareTo(orderedAmount) < 0) {
                throw new BusinessException(
                    "WORKS purchase order requires fully paid work completion coverage before close"
                );
            }
            if (!serviceClaimRepository
                .findByTenantIdAndPurchaseOrderItemIdAndStatusNotAndIsDeletedFalse(
                    tenantId,
                    item.getId(),
                    "CLOSED"
                )
                .isEmpty()) {
                throw new BusinessException("WORKS purchase order cannot close with open service claims");
            }
        }
    }

    private void reconcileServicePurchaseOrderLines(PurchaseOrder po, List<PurchaseOrderItem> items) {
        if (po.getRequestType() != RequestType.SERVICE || items.isEmpty()) {
            return;
        }
        List<ServiceAcceptanceAct> acceptedActs = serviceAcceptanceActRepository
            .findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
                TenantContext.currentTenant(),
                po.getId(),
                List.of("APPROVED", "SENT_TO_PAYMENT", "PAID")
            );
        ServiceAcceptanceAct acceptedAct = acceptedActs.isEmpty() ? null : acceptedActs.get(0);
        for (PurchaseOrderItem item : items) {
            boolean changed = false;
            if (item.getLineType() != ProcurementLineType.SERVICE) {
                item.setLineType(ProcurementLineType.SERVICE);
                changed = true;
            }
            if (acceptedAct != null && item.getServiceActId() == null) {
                item.setServiceActId(acceptedAct.getId());
                changed = true;
            }
            if (acceptedAct != null && item.getServiceOrderId() == null && acceptedAct.getServiceOrderId() != null) {
                item.setServiceOrderId(acceptedAct.getServiceOrderId());
                changed = true;
            }
            if (acceptedAct != null && (item.getStatus() == null || "OPEN".equals(item.getStatus()))) {
                item.setStatus("SERVICE_ACT_ACCEPTED");
                changed = true;
            }
            if (changed) {
                itemRepository.save(item);
            }
        }
    }

    @Transactional
    public PurchaseOrderResponse cancel(UUID id) {
        PurchaseOrder po = findEntity(id);
        // UX-LIVE-04: enforce CANCEL action policy
        purchaseOrderActionPolicy.requireActionAllowed(po, PurchaseOrderWorkspaceActionKey.CANCEL);
        if (PurchaseOrderStatus.RECEIVED.name().equals(po.getStatus()) || PurchaseOrderStatus.CLOSED.name().equals(po.getStatus())) {
            throw new BusinessException("Received or closed purchase order cannot be cancelled");
        }
        reverseBudgetCommitmentsOnCancel(po);
        po.setStatus(PurchaseOrderStatus.CANCELLED.name());
        supplierPoCancellationService.cancel(id);
        auditService.log("PurchaseOrder", id, "CANCEL", po.getCode(), null, null);
        return toResponse(repository.save(po));
    }
    private BigDecimal amount(PurchaseOrder po) {
        return po.getTotalAmount() == null ? BigDecimal.ZERO : po.getTotalAmount();
    }

    private void recordBudgetCommitmentsOnSend(PurchaseOrder po) {
        itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), po.getId())
            .forEach(item -> {
                releaseSourceReservationsForPoItem(po, item);
                budgetItemService.commitIdempotent(
                    item.getBudgetItemId(),
                    lineAmount(item),
                    "PurchaseOrder " + po.getCode(),
                    "PurchaseOrder",
                    po.getId(),
                    item.getId(),
                    "PurchaseOrderItem:" + item.getId(),
                    "COMMIT_SENT"
                );
            });
    }

    private void releaseSourceReservationsForPoItem(PurchaseOrder po, PurchaseOrderItem item) {
        purchaseOrderSourceAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), po.getCompanyId(), item.getId())
            .forEach(allocation -> purchaseRequestItemRepository.findByIdAndIsDeletedFalse(allocation.getPurchaseRequestItemId())
                .filter(source -> TenantContext.currentTenant().equals(source.getTenantId()))
                .filter(source -> Objects.equals(source.getCompanyId(), po.getCompanyId()))
                .ifPresent(source -> budgetItemService.releaseIdempotent(
                    source.getBudgetItemId(),
                    nvl(allocation.getOrderedQuantity()).multiply(nvl(source.getEstimatedPrice())),
                    "PurchaseOrder " + po.getCode(),
                    "PurchaseOrder",
                    po.getId(),
                    allocation.getPurchaseRequestItemId(),
                    "PurchaseRequestItem:" + allocation.getPurchaseRequestItemId(),
                    "RELEASE_PO_SENT:" + item.getId()
                )));
    }

    private void reverseBudgetCommitmentsOnCancel(PurchaseOrder po) {
        itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), po.getId())
            .forEach(item -> budgetItemService.reverseCommitmentIdempotent(
                item.getBudgetItemId(),
                openLineAmount(item),
                "PurchaseOrder " + po.getCode(),
                "PurchaseOrder",
                po.getId(),
                item.getId(),
                "PurchaseOrderItem:" + item.getId(),
                "REVERSE_CANCELLED"
            ));
    }

    private BigDecimal lineAmount(PurchaseOrderItem item) {
        return nvl(item.getQuantity()).multiply(nvl(item.getUnitPrice()));
    }

    private BigDecimal openLineAmount(PurchaseOrderItem item) {
        BigDecimal openQuantity = nvl(item.getQuantity()).subtract(nvl(item.getReceivedQuantity())).max(BigDecimal.ZERO);
        return openQuantity.multiply(nvl(item.getUnitPrice()));
    }

    private BigDecimal materialAmount(UUID purchaseOrderId) {
        List<PurchaseOrderItem> items = itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrderId);
        if (items.isEmpty()) {
            return repository.findByIdAndIsDeletedFalse(purchaseOrderId).map(this::amount).orElse(BigDecimal.ZERO);
        }
        return items.stream()
            .filter(this::isMaterialLine)
            .map(item -> nvl(item.getQuantity()).multiply(nvl(item.getUnitPrice())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void validateReceiptDestination(
        PurchaseOrder po,
        UUID warehouseId,
        UUID storageLocationId,
        UUID storageBinId,
        boolean requireStorageLocation
    ) {
        if (requireStorageLocation && storageLocationId == null) {
            throw new BusinessException(
                "Storage location is required for goods receipt",
                "goodsIncome.receipt.storageLocationRequired");
        }
        Warehouse warehouse = warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), po.getCompanyId(), warehouseId
        ).orElseThrow(() -> new BusinessException("Receipt warehouse not found in purchase order company: " + warehouseId));
        if (!roleDataScopeService.canAccessWarehouse(warehouse.getId())) {
            throw new BusinessException("Current user cannot receive into warehouse: " + warehouseId);
        }
        if (storageLocationId != null) {
            StorageLocation location = storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), po.getCompanyId(), storageLocationId
            ).orElseThrow(() -> new BusinessException("Receipt storage location not found: " + storageLocationId));
            if (!Objects.equals(location.getWarehouseId(), warehouseId)) {
                throw new BusinessException("Storage location warehouse must match receipt warehouse");
            }
        }
        if (storageBinId == null) return;
        StorageBin storageBin = storageBinRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), storageBinId
        ).filter(bin -> Objects.equals(bin.getCompanyId(), po.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Receipt storage bin not found: " + storageBinId));
        if (!Objects.equals(storageBin.getWarehouseId(), warehouseId)) {
            throw new BusinessException("Storage bin warehouse must match receipt warehouse");
        }
        if (storageLocationId != null && !Objects.equals(storageBin.getStorageLocationId(), storageLocationId)) {
            throw new BusinessException("Storage bin must belong to receipt storage location");
        }
    }
    private String currency(PurchaseOrder po) {
        return po.getCurrencyCode() == null || po.getCurrencyCode().isBlank() ? "UZS" : po.getCurrencyCode();
    }

    private BigDecimal effectiveExchangeRate(PurchaseOrder po) {
        return po.getExchangeRate() == null || po.getExchangeRate().signum() <= 0
            ? BigDecimal.ONE : po.getExchangeRate();
    }

    private UUID supplierId(PurchaseOrder po) {
        return po.getSupplier() == null ? po.getSupplierId() : po.getSupplier().getId();
    }

    /**
     * UX-LIVE-01: structured error context (purchaseOrderId/Code, supplierId/Code/Name)
     * attached to receipt-flow BusinessExceptions so the FE can keep the originating
     * document visible when an action fails instead of losing it behind a bare error toast.
     */
    private Map<String, Object> receiptErrorContext(PurchaseOrder po) {
        if (po == null) {
            return Map.of();
        }
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        if (po.getId() != null) {
            context.put("purchaseOrderId", po.getId());
        }
        if (po.getCode() != null) {
            context.put("purchaseOrderCode", po.getCode());
        }
        UUID resolvedSupplierId = supplierId(po);
        if (resolvedSupplierId != null) {
            context.put("supplierId", resolvedSupplierId);
        }
        Supplier supplier = po.getSupplier();
        if (supplier != null) {
            if (supplier.getCode() != null) {
                context.put("supplierCode", supplier.getCode());
            }
            if (supplier.getName() != null) {
                context.put("supplierName", supplier.getName());
            }
        }
        return context;
    }

    private BusinessException receiptError(PurchaseOrder po, String message, String messageKey, Object... messageArgs) {
        return new BusinessException(message, messageKey, receiptErrorContext(po), messageArgs);
    }

    private UUID materialGroupId(PurchaseOrder po, PurchaseOrderItem item) {
        return materialRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), item.getMaterialId()
        ).filter(material -> Objects.equals(material.getCompanyId(), po.getCompanyId()))
            .map(Material::getMaterialGroupId)
            .orElse(null);
    }
    private BigDecimal openQuantity(PurchaseOrderItem item) {
        return FulfillmentQuantities.physicalReceiptRemaining(item);
    }

    private BigDecimal acceptedFulfillmentRemaining(PurchaseOrderItem item) {
        return FulfillmentQuantities.acceptedFulfillmentRemaining(item);
    }

    private String receiptLineStatus(PurchaseOrderItem item) {
        boolean hasException = nvl(item.getRejectedQuantity()).signum() > 0
            || nvl(item.getDamagedQuantity()).signum() > 0
            || trimToNull(item.getShortageReason()) != null
            || trimToNull(item.getAccreditationWarning()) != null;
        if (openQuantity(item).signum() <= 0) {
            return hasException ? "RECEIVED_WITH_EXCEPTIONS" : "RECEIVED";
        }
        return nvl(item.getReceivedQuantity()).signum() > 0 || hasException ? "PARTIALLY_RECEIVED" : item.getStatus();
    }

    private boolean isMaterialLine(PurchaseOrderItem item) {
        ProcurementLineType lineType = item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
        return lineType == ProcurementLineType.MATERIAL;
    }

    private boolean isTmbMaterialLine(PurchaseOrderItem item) {
        return isMaterialLine(item) && item.getMaterialTypeSnapshot() == MaterialType.TMB;
    }

    private void requireTmbReceiptLine(PurchaseOrder po, PurchaseOrderItem item) {
        if (item.getMaterialTypeSnapshot() == MaterialType.TMB) {
            return;
        }
        if (item.getMaterialTypeSnapshot() == MaterialType.FIXED_ASSET) {
            throw receiptError(
                po,
                "Fixed asset purchase order line is not eligible for goods receipt stock posting: " + item.getCode(),
                "FIXED_ASSET_GOODS_RECEIPT_FORBIDDEN",
                item.getId()
            );
        }
        throw receiptError(
            po,
            "Warehouse receipt is only allowed for TMB purchase order lines: " + item.getCode(),
            "TMB_STOCK_ONLY",
            item.getId()
        );
    }

    private boolean isServiceLine(PurchaseOrderItem item) {
        ProcurementLineType lineType = item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
        return lineType == ProcurementLineType.SERVICE;
    }

    private boolean isWorksPurchaseOrder(PurchaseOrder po) {
        List<PurchaseOrderItem> items = po == null || po.getId() == null
            ? List.of()
            : itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), po.getId());
        return isWorksPurchaseOrder(po, items);
    }

    private boolean isWorksPurchaseOrder(PurchaseOrder po, List<PurchaseOrderItem> items) {
        if (po == null || po.getRequestType() != RequestType.SERVICE || items == null) {
            return false;
        }
        return items.stream().anyMatch(item -> item.getLineType() == ProcurementLineType.WORK);
    }

    private boolean isGoodsPurchaseOrder(PurchaseOrder po) {
        return po == null || po.getRequestType() == null || po.getRequestType() == RequestType.GOODS;
    }


    private String receiptMovementCode(PurchaseOrder po, PurchaseOrderItem item) {

        String poToken = shortCodeToken(po == null ? null : po.getCode(), po == null ? null : po.getId());
        String itemToken = shortCodeToken(item == null ? null : item.getCode(), item == null ? null : item.getId());
        return ("GR-" + poToken + "-" + itemToken + "-" + UUID.randomUUID().toString().substring(0, 8))
            .toUpperCase(Locale.ROOT);
    }

    private String shortCodeToken(String code, UUID fallbackId) {
        String source = trimToNull(code);
        if (source == null && fallbackId != null) {
            source = fallbackId.toString();
        }
        if (source == null) {
            source = UUID.randomUUID().toString();
        }
        String normalized = source.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "");
        if (normalized.length() > 16) {
            normalized = normalized.substring(0, 8) + normalized.substring(normalized.length() - 8);
        }
        return normalized.isBlank() ? UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT) : normalized;
    }

    private boolean hasAcceptedServiceAct(PurchaseOrderItem item) {
        if (!isServiceLine(item) || item.getServiceActId() == null) {
            return false;
        }
        ServiceAcceptanceAct act = serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(item.getServiceActId())
            .filter(found -> TenantContext.currentTenant().equals(found.getTenantId()))
            .orElse(null);
        if (act == null || !List.of("APPROVED", "SENT_TO_PAYMENT", "PAID").contains(act.getStatus())) {
            return false;
        }
        return item.getServiceOrderId() == null || Objects.equals(item.getServiceOrderId(), act.getServiceOrderId());
    }
    private String resolveQualityStatus(BigDecimal rejected, BigDecimal damaged, BigDecimal physicalReceived, String shortageReason) {
        if (physicalReceived.signum() > 0 && (damaged.signum() > 0 || shortageReason != null)) {
            return "PENDING_INSPECTION";
        }
        if (physicalReceived.signum() > 0) {
            return "NOT_REQUIRED";
        }
        if (rejected.signum() > 0) {
            return "REJECTED";
        }
        if (damaged.signum() > 0) {
            return "HOLD";
        }
        return "NOT_RECEIVED";
    }

    private ReceiptQualityOutcome recordPendingInspection(
        PurchaseOrder po,
        PurchaseOrderItem item,
        UUID warehouseId,
        UUID storageLocationId,
        String qualityStatus,
        BigDecimal acceptedQuantity,
        UUID stockMovementId
    ) {
        if (!"PENDING_INSPECTION".equals(qualityStatus)
            || acceptedQuantity == null
            || acceptedQuantity.signum() <= 0
            || stockMovementId == null) {
            return null;
        }
        String code = "QI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        QualityInspectionCreateRequest request = new QualityInspectionCreateRequest();
        request.setCode(code);
        request.setName("Incoming inspection for " + (item.getName() == null || item.getName().isBlank() ? "received material" : item.getName()));
        request.setInspectionNumber(code);
        request.setInspectionType("INCOMING");
        request.setSupplierId(supplierId(po));
        request.setPurchaseOrderId(po.getId());
        request.setPurchaseOrderItemId(item.getId());
        request.setStockMovementId(stockMovementId);
        request.setMaterialId(item.getMaterialId());
        request.setWarehouseId(warehouseId);
        request.setStorageLocationId(storageLocationId);
        request.setReceivedQuantity(acceptedQuantity);
        request.setQuarantinedQuantity(acceptedQuantity);
        request.setSampleQuantity(BigDecimal.ZERO);
        request.setDueDate(java.time.LocalDate.now().plusDays(1));
        QualityInspectionResponse created = qualityInspectionService.create(request);
        QualityInspectionResponse quarantined = qualityInspectionService.startQuarantine(created.getId());
        return new ReceiptQualityOutcome(
            quarantined.getId(), quarantined.getInspectionNumber(), item.getId(), stockMovementId,
            acceptedQuantity, quarantined.getStatus());
    }

    private BigDecimal acceptedReceiptQuantity(BigDecimal physicalReceived, String qualityStatus) {
        if (physicalReceived == null || physicalReceived.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        String normalized = trimToNull(qualityStatus);
        if (normalized == null) {
            return BigDecimal.ZERO;
        }
        return switch (normalized.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_')) {
            case "ACCEPTED", "ACCEPTED_WITH_DEVIATION", "RELEASED", "PASSED", "NOT_REQUIRED" -> physicalReceived;
            default -> BigDecimal.ZERO;
        };
    }

    private void assertReceiptAccreditation(PurchaseOrder po, PurchaseOrderItem item) {
        UUID supplierId = po.getSupplier() != null ? po.getSupplier().getId() : po.getSupplierId();
        if (supplierId == null) {
            throw new BusinessException("Supplier is missing on purchase order");
        }
        requireExistingSupplierObligationAllowed(supplierId, "GOODS_RECEIPT");
    }
    private List<UUID> materialGroupIds(PurchaseOrder po, PurchaseOrderItem item) {
        return materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), item.getMaterialId())
            .filter(material -> Objects.equals(material.getCompanyId(), po.getCompanyId()))
            .map(Material::getMaterialGroupId)
            .filter(Objects::nonNull)
            .map(List::of)
            .orElseGet(List::of);
    }
private AdvanceShippingNotice validateRequiredReceiptAsn(PurchaseOrder po, UUID asnId) {
        boolean auctionReceipt = requiresConfirmedDelivery(po);
        if (asnId == null) {
            throw receiptError(
                po,
                auctionReceipt
                    ? "E-auction purchase order requires Delivery confirmed (ASN CONFIRMED) before goods receipt"
                    : "Confirmed ASN is required for material purchase order receipt",
                "goodsIncome.asn.required");
        }
        AdvanceShippingNotice asn = asnRepository.findByIdAndIsDeletedFalse(asnId)
            .filter(item -> !item.isDeleted())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> Objects.equals(item.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> receiptError(
                po,
                "ASN not found for receipt: " + asnId,
                "goodsIncome.asn.notFound",
                asnId));
        if (!Objects.equals(asn.getPurchaseOrderId(), po.getId())) {
            throw receiptError(
                po,
                "ASN does not belong to purchase order",
                "goodsIncome.asn.mismatch",
                asnId);
        }
        if (!isDeliveryConfirmedStatus(asn.getStatus())) {
            throw receiptError(
                po,
                auctionReceipt
                    ? "E-auction purchase order requires a confirmed delivery (ASN) before goods receipt"
                    : "ASN must be CONFIRMED before first purchase order receipt",
                "goodsIncome.asn.notConfirmed",
                asn.getStatus());
        }
        return asn;
    }

    private void validateReceiptAccreditation(PurchaseOrder po, List<PurchaseOrderItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        var eligibility = supplierEligibilityService == null
            ? null
            : supplierEligibilityService.evaluateExistingObligation(po.getSupplierId(), "GOODS_RECEIPT");
        if (eligibility == null) {
            return;
        }
        if (!eligibility.existingObligationsAllowed()) {
            throw receiptError(
                po,
                eligibility.summaryReason(),
                "goodsIncome.supplierEligibility.blocked",
                po.getId());
        }
    }

    private boolean requiresConfirmedDelivery(PurchaseOrder po) {
        if (po == null || po.getId() == null) {
            return false;
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        boolean auctionAward = procurementAwardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                tenantId, companyId, po.getId()
            )
            .map(outcome -> outcome.getSourceType() == ProcurementAwardSourceType.MANAGED_AUCTION)
            .orElse(false);
        if (auctionAward) {
            return true;
        }
        return procurementProcedureRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(tenantId, companyId, po.getId())
            .map(procedure -> {
                try {
                    return ProcurementMethodType.valueOf(procedure.getMethod()).executionPath()
                        == ProcurementExecutionPath.AUCTION;
                } catch (RuntimeException ignored) {
                    return ProcurementMethodType.E_AUCTION.name().equals(procedure.getMethod());
                }
            })
            .orElse(false);
    }

    private boolean isDeliveryConfirmedStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return AdvanceShippingNoticeStatus.CONFIRMED.name().equals(normalized)
            || AdvanceShippingNoticeStatus.RECEIVED.name().equals(normalized)
            || AdvanceShippingNoticeStatus.PARTIALLY_RECEIVED.name().equals(normalized);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void normalizeCurrency(PurchaseOrder entity) {
        if (entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()) {
            entity.setCurrencyCode("UZS");
        } else {
            entity.setCurrencyCode(entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }
        if (entity.getExchangeRate() == null || entity.getExchangeRate().signum() <= 0) {
            entity.setExchangeRate(BigDecimal.ONE);
        }
    }

    private BigDecimal receiptUnitCost(PurchaseOrder po, PurchaseOrderItem item) {
        return nvl(item.getUnitPrice())
            .multiply(po.getExchangeRate() == null || po.getExchangeRate().signum() <= 0 ? BigDecimal.ONE : po.getExchangeRate())
            .setScale(4, java.math.RoundingMode.HALF_UP);
    }
    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private boolean createRevision(PurchaseOrder po, ObjectNode before, ObjectNode after, String reason) {
        ObjectNode diff = objectMapper.createObjectNode();
        before.fieldNames().forEachRemaining(field -> {
            if (!before.get(field).equals(after.get(field))) {
                ObjectNode change = diff.putObject(field);
                change.set("from", before.get(field));
                change.set("to", after.get(field));
            }
        });
        if (diff.isEmpty()) {
            return false;
        }
        long count = revisionRepository.countByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            po.getId()
        );
        PurchaseOrderRevision revision = new PurchaseOrderRevision();
        revision.setCode(po.getCode() + "-REV-" + (count + 1));
        revision.setName(BusinessNameGenerator.workflowDocumentName("PurchaseOrderRevision", po.getName()));
        revision.setStatus("RECORDED");
        revision.setPurchaseOrder(po);
        revision.setRevisionNumber((int) count + 1);
        revision.setReason(reason);
        revision.setDiffJson(diff.toString());
        revision.setSnapshotJson(after.toString());
        revision.setRevisedAt(LocalDateTime.now());
        revisionRepository.save(revision);
        auditService.log("PurchaseOrder", po.getId(), "REVISION", revision.getCode(), null, null);
        return true;
    }
    private ObjectNode snapshot(PurchaseOrder po) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("supplierId", po.getSupplierId() == null ? null : po.getSupplierId().toString());
        node.put("purchaseRequestId", po.getPurchaseRequestId() == null ? null : po.getPurchaseRequestId().toString());
        node.put("contractId", po.getContractId() == null ? null : po.getContractId().toString());
        node.put("orderDate", po.getOrderDate() == null ? null : po.getOrderDate().toString());
        node.put("expectedDeliveryDate", po.getExpectedDeliveryDate() == null ? null : po.getExpectedDeliveryDate().toString());
        node.put("totalAmount", amount(po).toPlainString());
        node.put("currencyCode", currency(po));
        node.put("status", po.getStatus());
        return node;
    }
    @Transactional(readOnly = true)
    public Page<PurchaseOrderResponse> search(PurchaseOrderSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        boolean departmentOnly = dataScope.hasDocumentScope() && !roleDataScopeService.hasWarehouseAccess();
        var pageRequest = departmentOnly ? Pageable.unpaged() : pageable;
        var spec = ProcurementSearchSpecifications.build(
            PurchaseOrder.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        var records = repository.findAll(spec, pageRequest);
        if (!departmentOnly) {
            return records.map(this::toSearchResponse);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> {
                if (entity.getPurchaseRequest() == null) {
                    return roleDataScopeService.canAccessDocument(entity.getCreatedBy(), null, null);
                }
                return roleDataScopeService.canAccessDocument(
                    entity.getCreatedBy(),
                    entity.getPurchaseRequest().getRequestedBy(),
                    entity.getPurchaseRequest().getDepartmentId()
                );
            },
            this::toSearchResponse
        );
    }
    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(UUID id) {
        return toResponse(findEntity(id));
    }
    @Transactional
    public PurchaseOrderResponse create(PurchaseOrderCreateRequest dto) {
        if (dto.getPurchaseRequestId() != null) {
            purchaseOrderDemandCyclePolicy.requireApprovedCycleForPurchaseRequest(dto.getPurchaseRequestId());
        } else {
            purchaseOrderDemandCyclePolicy.requireApprovedCycleForContract(dto.getContractId());
        }
        PurchaseOrder entity = mapper.toEntity(dto);
        recalculateTotals(entity, dto);
        beforeCreate(entity, dto);
        PurchaseOrder saved = repository.save(entity);
        saveSubmittedItems(saved, dto.getItems(), false);
        serviceOrderLinkService.ensureForPurchaseOrder(saved, "CREATE_FROM_SERVICE_PURCHASE_ORDER");
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        PurchaseOrder entity = findEntity(id);
        // UX-LIVE-04: enforce DELETE action policy
        purchaseOrderActionPolicy.requireActionAllowed(entity, PurchaseOrderWorkspaceActionKey.DELETE);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public PurchaseOrderResponse restore(UUID id) {
        PurchaseOrder entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        PurchaseOrder saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }
    protected PurchaseOrder findEntity(UUID id) {
        PurchaseOrder entity = repository.findByIdAndIsDeletedFalse(id)
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
        UUID departmentId = entity.getPurchaseRequest() == null ? null : entity.getPurchaseRequest().getDepartmentId();
        String owner = entity.getPurchaseRequest() == null ? null : entity.getPurchaseRequest().getRequestedBy();
        if (!roleDataScopeService.hasWarehouseAccess()
            && !roleDataScopeService.canAccessDocument(entity.getCreatedBy(), owner, departmentId)) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected void beforeUpdate(PurchaseOrder entity, PurchaseOrderUpdateRequest dto) {
        if (entity.getRequestType() == null) {
            entity.setRequestType(resolveRequestType(entity.getPurchaseRequestId()));
        }
        normalizeCurrency(entity);
        entity.setName(BusinessDisplayNamePolicy.requireWorkflowDocumentName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode(), purchaseRequestName(entity.getPurchaseRequestId())
        ));
    }
    protected boolean companyScoped() {
        return true;
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder entity) {
        PurchaseOrderResponse response = mapper.toResponse(entity);
        if (response == null) {
            return null;
        }
        annotateSendReadiness(response, entity);
        annotateCollaboration(response, entity);
        annotateLifecycle(response, entity);
        return procurementLineageEnricher.enrichPurchaseOrder(response);
    }

    private void annotateCollaboration(PurchaseOrderResponse response, PurchaseOrder entity) {
        if (response == null || entity == null) {
            return;
        }
        SupplierPoDispatch currentDispatch = supplierPoDispatchService.findCurrent(entity.getId()).orElse(null);
        if (currentDispatch == null) {
            response.setLegacy(supplierPoDispatchService.neverDispatched(entity.getId()));
            response.setCollaborationStatus("NOT_SENT");
            return;
        }
        response.setLegacy(false);
        response.setDispatchId(currentDispatch.getId());
        response.setDispatchStatus(currentDispatch.getDispatchStatus());
        SupplierPoResponse currentResponse = supplierPoResponseRepository
            .findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), currentDispatch.getId()
            ).orElse(null);
        boolean confirmedOpen = !supplierPoCommitmentRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getId(), List.of("CONFIRMED")
            ).isEmpty();
        response.setCollaborationStatus(
            supplierPoCollaborationQueryService.resolveStatus(currentDispatch, currentResponse, false, confirmedOpen)
        );
    }

    private void annotateLifecycle(PurchaseOrderResponse response, PurchaseOrder entity) {
        if (response == null || entity == null) {
            return;
        }
        try {
            List<PurchaseOrderItem> items = itemRepository
                .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), entity.getId());
            response.setFulfillmentState(fulfillmentState(entity, items));
            response.setInvoicingState(invoicingState(entity));
            response.setPaymentState(paymentState(entity));
            CloseReadiness closeReadiness = closeReadiness(entity, items);
            response.setCloseReady(closeReadiness.ready());
            response.setCloseBlockedReason(closeReadiness.blockedReason());
            response.setAdministrativeCloseState(administrativeCloseState(entity, closeReadiness));
        } catch (RuntimeException exception) {
            log.error(
                "Could not evaluate lifecycle state for purchase order {}",
                entity.getId(),
                exception
            );
            response.setFulfillmentState("UNKNOWN");
            response.setInvoicingState("UNKNOWN");
            response.setPaymentState("UNKNOWN");
            response.setAdministrativeCloseState("UNKNOWN");
            response.setCloseReady(false);
            response.setCloseBlockedReason("Purchase order lifecycle state is temporarily unavailable");
        }
    }

    private String fulfillmentState(PurchaseOrder po, List<PurchaseOrderItem> items) {
        if (PurchaseOrderStatus.CLOSED.name().equals(po.getStatus())) {
            return "FULFILLED";
        }
        if (isWorksPurchaseOrder(po, items)) {
            if (PurchaseOrderStatus.COMPLETED.name().equals(po.getStatus())) {
                return "FULFILLED";
            }
            return PurchaseOrderStatus.SENT.name().equals(po.getStatus())
                ? "IN_PROGRESS"
                : "PENDING_FULFILLMENT";
        }
        if (items.isEmpty()) {
            return "NO_LINES";
        }
        boolean hasMaterialLines = items.stream().anyMatch(this::isMaterialLine);
        boolean hasServiceLines = items.stream().anyMatch(this::isServiceLine);
        boolean allMaterialFulfilled = !hasMaterialLines
            || items.stream().filter(this::isMaterialLine).allMatch(item -> acceptedFulfillmentRemaining(item).signum() <= 0);
        boolean allServiceFulfilled = !hasServiceLines
            || items.stream().filter(this::isServiceLine).allMatch(this::hasAcceptedServiceAct);
        if (allMaterialFulfilled && allServiceFulfilled) {
            return "FULFILLED";
        }
        boolean anyProgress = items.stream()
            .anyMatch(item -> nvl(item.getReceivedQuantity()).signum() > 0
                || nvl(item.getRejectedQuantity()).signum() > 0
                || nvl(item.getDamagedQuantity()).signum() > 0
                || hasAcceptedServiceAct(item));
        return anyProgress ? "PARTIALLY_FULFILLED" : "PENDING_FULFILLMENT";
    }

    private String invoicingState(PurchaseOrder po) {
        if (contractInvoiceRepository == null || po.getId() == null) {
            return "UNKNOWN";
        }
        List<ContractInvoice> invoices = contractInvoiceRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                po.getId()
            )
            .stream()
            .filter(invoice -> Objects.equals(po.getCompanyId(), invoice.getCompanyId()))
            .toList();
        if (invoices.isEmpty()) {
            return "NOT_INVOICED";
        }
        List<ContractInvoice> activeInvoices = invoices.stream()
            .filter(invoice -> !ContractInvoiceStatus.CANCELLED.name().equals(invoice.getStatus()))
            .toList();
        if (activeInvoices.isEmpty()) {
            return "INVOICING_CANCELLED";
        }
        if (activeInvoices.stream().allMatch(invoice -> ContractInvoiceStatus.PAID.name().equals(invoice.getStatus()))) {
            return "INVOICE_PAID";
        }
        if (activeInvoices.stream().anyMatch(invoice -> ContractInvoiceStatus.PARTIALLY_PAID.name().equals(invoice.getStatus()))) {
            return "INVOICE_PARTIALLY_PAID";
        }
        return "INVOICE_OPEN";
    }

    private String paymentState(PurchaseOrder po) {
        if (contractPaymentRepository == null || po.getId() == null) {
            return "UNKNOWN";
        }
        List<ContractPayment> payments = contractPaymentRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                po.getId()
            )
            .stream()
            .filter(payment -> Objects.equals(po.getCompanyId(), payment.getCompanyId()))
            .toList();
        if (payments.isEmpty()) {
            return "NOT_REQUESTED";
        }
        List<ContractPayment> activePayments = payments.stream()
            .filter(payment -> !ContractPaymentStatus.CANCELLED.name().equals(payment.getStatus()))
            .toList();
        if (activePayments.isEmpty()) {
            return "PAYMENT_CANCELLED";
        }
        if (activePayments.stream().allMatch(payment -> ContractPaymentStatus.PAID.name().equals(payment.getStatus()))) {
            return "PAID";
        }
        if (activePayments.stream().anyMatch(payment -> ContractPaymentStatus.PAID.name().equals(payment.getStatus()))) {
            return "PARTIALLY_PAID";
        }
        return "PAYMENT_IN_PROGRESS";
    }

    private String administrativeCloseState(PurchaseOrder po, CloseReadiness closeReadiness) {
        if (PurchaseOrderStatus.CLOSED.name().equals(po.getStatus())) {
            return "CLOSED";
        }
        if (PurchaseOrderStatus.CANCELLED.name().equals(po.getStatus())) {
            return "CANCELLED";
        }
        return closeReadiness.ready() ? "READY_TO_CLOSE" : "CLOSE_BLOCKED";
    }

    private PurchaseOrderResponse toSearchResponse(PurchaseOrder entity) {
        PurchaseOrderResponse response = mapper.toResponse(entity);
        try {
            annotateSendReadiness(response, entity);
        } catch (RuntimeException ex) {
            log.error(
                "Could not evaluate send readiness for purchase order {} while building the registry",
                entity == null ? null : entity.getId(),
                ex
            );
            if (response != null) {
                response.setSendReady(false);
                response.setSendBlockedReason("Send readiness is temporarily unavailable");
            }
        }
        return response;
    }

    private void annotateSendReadiness(PurchaseOrderResponse response, PurchaseOrder entity) {
        if (response == null || entity == null) {
            return;
        }
        if (!PurchaseOrderStatus.APPROVED.name().equals(entity.getStatus())) {
            response.setSendReady(false);
            response.setSendBlockedReason("Purchase order must be APPROVED before sending");
            return;
        }
        List<PurchaseOrderItem> items = itemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), entity.getId());
        AccreditationDemand accreditationDemand = dispatchReadinessService.accreditationDemand(items);
        if (supplierEligibilityService == null) {
            String blockedReason = supplierAccreditationService.supplierEligibilityBlockedReasonForDemand(
                entity.getSupplierId(),
                accreditationDemand,
                "purchase order dispatch"
            );
            response.setSendReady(blockedReason == null);
            response.setSendBlockedReason(blockedReason);
            return;
        }
        var eligibility = supplierEligibilityService.evaluateNewBusiness(
            entity.getSupplierId(),
            accreditationDemand,
            "PO_DISPATCH"
        );
        response.setSendReady(eligibility.newBusinessAllowed());
        response.setSendBlockedReason(eligibility.summaryReason());
    }

    private void requireExistingSupplierObligationAllowed(UUID supplierId, String operation) {
        if (supplierEligibilityService != null) {
            supplierEligibilityService.requireExistingObligationAllowed(supplierId, operation);
            return;
        }
        supplierAccreditationService.assertSupplierEligible(supplierId, List.of(), "goods receipt");
    }

    private void saveSubmittedItems(PurchaseOrder purchaseOrder, List<PurchaseOrderItemCreateRequest> items, boolean replaceExisting) {
        if (items == null) {
            return;
        }
        UUID purchaseOrderId = purchaseOrder.getId();
        List<PurchaseOrderItem> existingItems = List.of();
        if (replaceExisting) {
            existingItems = itemRepository
                .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseOrderId);
            softDeleteSourceAllocations(purchaseOrder, existingItems);
            existingItems.forEach(item -> {
                    item.softDelete();
                    itemRepository.save(item);
                });
        }
        boolean worksPurchaseOrder = isWorksPurchaseOrder(purchaseOrder, existingItems);
        items.forEach(item -> {
            PurchaseRequestItem source = requirePurchaseRequestLineage(purchaseOrder, item);
            preserveGoodsMaterialLinkage(purchaseOrder, item, source);
            if (source != null) {
                item.setMaterialTypeSnapshot(source.getMaterialTypeSnapshot());
            }
            item.setPurchaseOrderId(purchaseOrderId);
            if (worksPurchaseOrder) {
                item.setLineType(ProcurementLineType.WORK.name());
                item.setMaterialId(null);
            }
            var savedItem = itemService.create(item);
            saveDirectSourceAllocation(purchaseOrder, savedItem.getId(), item, source);
        });
    }

    private PurchaseRequestItem requirePurchaseRequestLineage(PurchaseOrder purchaseOrder, PurchaseOrderItemCreateRequest item) {
        if (purchaseOrder == null || purchaseOrder.getPurchaseRequestId() == null || item == null) {
            return null;
        }
        if (item.getPurchaseRequestItemId() == null) {
            throw new BusinessException(
                "purchaseRequestItemId is required for every purchase order line when purchaseOrder.purchaseRequestId is set"
            );
        }
        PurchaseRequestItem source = purchaseRequestItemRepository.findByIdAndIsDeletedFalse(item.getPurchaseRequestItemId())
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> Objects.equals(candidate.getCompanyId(), purchaseOrder.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Purchase request item not found for purchase order lineage: " + item.getPurchaseRequestItemId()));
        if (!Objects.equals(source.getPurchaseRequestId(), purchaseOrder.getPurchaseRequestId())) {
            throw new BusinessException("Purchase order item lineage must belong to the purchase order purchase request");
        }
        return source;
    }

    private void preserveGoodsMaterialLinkage(
        PurchaseOrder purchaseOrder,
        PurchaseOrderItemCreateRequest item,
        PurchaseRequestItem source
    ) {
        if (!isGoodsPurchaseOrder(purchaseOrder) || item == null) {
            return;
        }
        if (source != null) {
            if (source.getMaterialId() == null) {
                throw new BusinessException(ProcurementLineValidationService.GOODS_PO_ITEM_REQUIRES_MATERIAL);
            }
            item.setMaterialId(source.getMaterialId());
        }
        if (item.getMaterialId() == null) {
            throw new BusinessException(ProcurementLineValidationService.GOODS_PO_ITEM_REQUIRES_MATERIAL);
        }
    }

    private void softDeleteSourceAllocations(PurchaseOrder purchaseOrder, List<PurchaseOrderItem> existingItems) {
        List<UUID> itemIds = existingItems.stream()
            .map(PurchaseOrderItem::getId)
            .filter(Objects::nonNull)
            .toList();
        if (itemIds.isEmpty()) {
            return;
        }
        purchaseOrderSourceAllocationRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdInAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseOrder.getCompanyId(), itemIds)
            .forEach(allocation -> {
                allocation.softDelete();
                purchaseOrderSourceAllocationRepository.save(allocation);
            });
    }

    private void saveDirectSourceAllocation(
        PurchaseOrder purchaseOrder,
        UUID purchaseOrderItemId,
        PurchaseOrderItemCreateRequest item,
        PurchaseRequestItem source
    ) {
        UUID purchaseRequestItemId = item.getPurchaseRequestItemId();
        if (purchaseRequestItemId == null) {
            return;
        }
        if (purchaseOrder.getPurchaseRequestId() == null) {
            throw new BusinessException("Purchase request is required when purchase order item carries purchase request lineage");
        }
        if (source == null) {
            source = requirePurchaseRequestLineage(purchaseOrder, item);
        }

        PurchaseOrderSourceAllocation allocation = new PurchaseOrderSourceAllocation();
        allocation.setCode("PO-SRC-" + UUID.randomUUID().toString().substring(0, 8));
        allocation.setName("Direct PO source allocation");
        allocation.setStatus("ACTIVE");
        allocation.setPurchaseOrderItemId(purchaseOrderItemId);
        allocation.setPurchaseRequestItemId(purchaseRequestItemId);
        allocation.setOrderedQuantity(nvl(item.getQuantity()));
        purchaseOrderSourceAllocationRepository.save(allocation);
    }

    private void recalculateTotals(PurchaseOrder entity, PurchaseOrderCreateRequest dto) {
        if (dto.getItems() == null) {
            return;
        }
        BigDecimal total = dto.getItems().stream()
            .map(item -> nvl(item.getQuantity()).multiply(nvl(item.getUnitPrice())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        entity.setTotalAmount(total);
    }

    private RequestType resolveRequestType(UUID purchaseRequestId) {
        if (purchaseRequestId == null) {
            return RequestType.GOODS;
        }
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)
            .map(request -> request.getRequestType() == null ? RequestType.GOODS : request.getRequestType())
            .orElse(RequestType.GOODS);
    }

    private String purchaseRequestName(UUID purchaseRequestId) {
        if (purchaseRequestId == null) {
            return null;
        }
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)
            .filter(request -> TenantContext.currentTenant().equals(request.getTenantId()))
            .filter(request -> com.enterprise.mm.security.AccessScope.canAccessCompany(request.getCompanyId()))
            .map(request -> request.getName())
            .orElse(null);
    }
}

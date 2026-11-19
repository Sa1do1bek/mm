package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.erp.ErpExchangeHooks;
import com.enterprise.mm.erp.ErpExchangeService;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.TransferShipment;
import com.enterprise.mm.entity.TransferShipmentAllocation;
import com.enterprise.mm.entity.TransferAllocationClearance;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.transfer.TransferActionPolicy;
import com.enterprise.mm.service.transfer.TransferActionPolicy.Action;
import com.enterprise.mm.service.approval.ApprovedOperationContext;
import com.enterprise.mm.service.approval.TransferApproveOperationHandler;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.entity.TransferDocumentLine;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TransferStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.TransferDocumentMapper;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.TransferDocumentLineRepository;
import com.enterprise.mm.repository.TransferDocumentRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.transfer.TransferProgressService;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferDocumentService {
    private final com.enterprise.mm.service.UnitOfMeasureDisplayService unitDisplay;
    private static final String ENTITY_NAME = "TransferDocument";

    private final TransferDocumentRepository repository;
    private final TransferDocumentLineRepository lineRepository;
    private final TransferDocumentMapper mapper;
    private final WarehouseRepository warehouseRepository;
    private final MaterialRepository materialRepository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;
    private final RoleDataScopeService roleDataScopeService;
    private final TransferPostingService transferPostingService;
    private final StockPickingAllocationService stockPicking;
    private final com.enterprise.mm.repository.TransferShipmentRepository shipmentRepository;
    private final com.enterprise.mm.repository.TransferShipmentAllocationRepository allocationRepository;
    private final com.enterprise.mm.repository.TransferAllocationClearanceRepository clearanceRepository;
    private final com.enterprise.mm.service.transfer.TransferTransitLedgerService transitLedger;
    private final TransferActionPolicy actionPolicy;
    private final com.enterprise.mm.service.transfer.TransferProgressService progress;
    private final MaterialUnitEligibilityService materialUnitEligibilityService;
    private final com.enterprise.mm.warehouse.history.IssueSourceTransferGuard issueSourceGuard;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ErpExchangeService erpExchange;
    private final com.enterprise.mm.service.transfer.TransferReceiptService receipts;
    @org.springframework.beans.factory.annotation.Value("${app.warehouse.transfers.shipment-flow-enabled:false}")
    private boolean shipmentFlowEnabled;


    @Transactional(readOnly = true)
    public Page<TransferDocumentResponse> search(TransferSearchRequest request, Pageable pageable) {
        TransferSearchRequest effective = request == null
            ? new TransferSearchRequest(null, List.of(), null, null, null, null, null)
            : request;
        effective = new TransferSearchRequest(
            effective.search(),
            effective.status(),
            roleDataScopeService.effectiveWarehouseId(effective.warehouseId()),
            effective.sourceWarehouseId(),
            effective.destinationWarehouseId(),
            effective.dateFrom(),
            effective.dateTo(),
            effective.action()
        );
        if (effective.action() != null && !List.of("dispatch", "receive", "tasks").contains(effective.action())) throw new BadRequestException("Unsupported transfer queue action");
        if (pageable.isUnpaged()) return new org.springframework.data.domain.PageImpl<>(repository.findAll(spec(effective),
            org.springframework.data.domain.Sort.by("createdAt").descending().and(org.springframework.data.domain.Sort.by("id"))).stream().map(this::response).toList());
        var stablePage = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
            pageable.getSort().isSorted() ? pageable.getSort().and(org.springframework.data.domain.Sort.by("id")) : org.springframework.data.domain.Sort.by("createdAt").descending().and(org.springframework.data.domain.Sort.by("id")));
        return repository.findAll(spec(effective), stablePage).map(this::response);
    }

    @Transactional(readOnly = true)
    public TransferDocumentResponse get(UUID id) {
        return response(findEntity(id));
    }

    @Transactional(readOnly = true)
    public TransferReceiptDtos.Context receiptContext(UUID id) {
        return receiptContext(get(id));
    }

    public TransferReceiptDtos.Context receiptContext(TransferDocumentResponse scopedDocument) {
        return receipts.describe(scopedDocument);
    }

    /** Internal review read: the approval worklist must authorize task assignment before calling. */
    @Transactional(readOnly = true)
    public TransferDocumentResponse getForApprovalReview(UUID id) {
        return response(findScopedEntity(id));
    }

    @Transactional
    public TransferDocumentResponse create(TransferCreateRequest request) {
        if (request == null) {
            throw new BadRequestException("Transfer request body is required");
        }
        actionPolicy.requireCreate(request.getSourceWarehouseId());
        if (request.getLines() == null || request.getLines().isEmpty()) throw new BadRequestException("Select at least one material");
        Warehouse source = requireWarehouse(request.getSourceWarehouseId());
        Warehouse destination = requireWarehouse(request.getDestinationWarehouseId());
        if (Objects.equals(source.getId(), destination.getId())) {
            throw new BadRequestException("Source and destination warehouses must be different");
        }
        transferPostingService.validateLocationSelection(source.getId(), request.getSourceLocationId(), null);
        transferPostingService.validateLocationSelection(destination.getId(), request.getDestinationLocationId(), null);
        TransferDocument entity = new TransferDocument();
        entity.setCode(blank(request.getCode()) ? "TR-" + shortId() : request.getCode());
        entity.setName(blank(request.getName()) ? "Stock transfer" : request.getName());
        entity.setDescription(request.getDescription());
        entity.setStatus(TransferStatus.DRAFT.name());
        entity.setSourceWarehouseId(source.getId());
        entity.setSourceLocationId(request.getSourceLocationId());
        entity.setDestinationWarehouseId(destination.getId());
        entity.setDestinationLocationId(request.getDestinationLocationId());
        entity.setPlannedDate(request.getPlannedDate());
        TransferDocument saved = repository.save(entity);

        int index = 0;
        for (TransferCreateRequest.Line requestLine : request.getLines()) {
            Material material = requireMaterial(requestLine.getMaterialId());
            StockUomPolicy.quantity(material, requestLine.getPlannedQuantity(), false);
            transferPostingService.validatePlanSource(source.getId(), material.getId(), requestLine.getStockBatchId(), firstNonNull(requestLine.getStorageLocationId(), request.getSourceLocationId()));
            TransferDocumentLine line = new TransferDocumentLine();
            line.setCode(saved.getCode() + "-" + (++index));
            line.setName(material.getName());
            line.setStatus("ACTIVE");
            line.setTransferDocumentId(saved.getId());
            line.setMaterialId(material.getId());
            line.setPlannedQuantity(requestLine.getPlannedQuantity());
            line.setDispatchedQuantity(BigDecimal.ZERO);
            line.setInTransitQuantity(BigDecimal.ZERO);
            line.setReceivedQuantity(BigDecimal.ZERO);
            line.setDamagedQuantity(BigDecimal.ZERO);
            line.setMissingQuantity(BigDecimal.ZERO);
            line.setSourceStockBatchId(requestLine.getStockBatchId());
            line.setSourceStorageLocationId(firstNonNull(requestLine.getStorageLocationId(), request.getSourceLocationId()));
            line.setDestinationStorageLocationId(request.getDestinationLocationId());
            lineRepository.save(line);
        }
        TransferDocumentResponse response = response(saved);
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE", saved.getCode(), null, response);
        return response;
    }

    @Transactional
    public TransferDocumentResponse submit(UUID id) {
        TransferDocument entity = actionPolicy.require(id, Action.SUBMIT);
        if (lines(id).isEmpty()) {
            throw new BadRequestException("Transfer must contain at least one line");
        }
        String oldSnapshot = snapshotSerializer.toJson(response(entity));
        entity.setStatus(TransferStatus.SUBMITTED.name());
        TransferDocument saved = repository.saveAndFlush(entity);
        TransferDocumentResponse response = response(saved);
        auditService.log(ENTITY_NAME, id, "SUBMIT", saved.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional
    public TransferDocumentResponse approve(UUID id, TransferApproveRequest request) {
        ApprovedOperationContext.require(TransferApproveOperationHandler.MODULE_KEY, id);
        TransferDocument entity = actionPolicy.requireApprovedOperation(id);
        requireExpectedVersion(entity, request == null ? null : request.expectedVersion());
        if (Objects.equals(normalized(currentUsername()), normalized(entity.getCreatedBy()))) {
            throw new AccessDeniedException("Creator cannot approve their own transfer document");
        }
        String oldSnapshot = snapshotSerializer.toJson(response(entity));
        entity.setStatus(TransferStatus.APPROVED.name());
        TransferDocument saved = repository.saveAndFlush(entity);
        TransferDocumentResponse response = response(saved);
        auditService.log(ENTITY_NAME, id, "APPROVE", saved.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional(readOnly = true)
    public void validateApproveForApproval(UUID id, Long expectedVersion) {
        // The preparer requests approval; separation of duties is enforced when the reviewer approves.
        TransferDocument entity = actionPolicy.lockAuthorized(id, Action.SUBMIT);
        actionPolicy.requireState(entity, Action.APPROVE);
        requireExpectedVersion(entity, expectedVersion);
    }

    @Transactional
    public TransferDocumentResponse edit(UUID id, com.enterprise.mm.payload.TransferEditRequest request) {
        var entity = actionPolicy.require(id, Action.EDIT);
        requireExpectedVersion(entity, request == null ? null : request.getExpectedVersion());
        requireNoShipmentActivity(entity);
        if (request.getLines() == null || request.getLines().isEmpty()) throw new BadRequestException("Select at least one material");
        actionPolicy.requireCreate(request.getSourceWarehouseId());
        var source = requireWarehouse(request.getSourceWarehouseId()); var destination = requireWarehouse(request.getDestinationWarehouseId());
        if (source.getId().equals(destination.getId())) throw new BadRequestException("Source and destination warehouses must be different");
        transferPostingService.validateLocationSelection(source.getId(), request.getSourceLocationId(), null);
        transferPostingService.validateLocationSelection(destination.getId(), request.getDestinationLocationId(), null);
        for (var input : request.getLines()) { StockUomPolicy.quantity(requireMaterial(input.getMaterialId()), input.getPlannedQuantity(), false); }
        issueSourceGuard.validateEdit(id, request);
        entity.setSourceWarehouseId(source.getId()); entity.setSourceLocationId(request.getSourceLocationId());
        entity.setDestinationWarehouseId(destination.getId()); entity.setDestinationLocationId(request.getDestinationLocationId());
        entity.setPlannedDate(request.getPlannedDate()); entity.setDescription(request.getDescription());
        if (!blank(request.getName())) entity.setName(request.getName());
        lineRepository.deleteAll(lines(id)); lineRepository.flush();
        int index = 0;
        for (var input : request.getLines()) {
            var material = requireMaterial(input.getMaterialId());
            var line = new TransferDocumentLine(); line.setName(material.getName()); line.setCode(entity.getCode() + "-" + ++index); line.setStatus("ACTIVE");
            line.setTransferDocumentId(id); line.setMaterialId(material.getId()); line.setPlannedQuantity(input.getPlannedQuantity());
            line.setSourceStockBatchId(input.getStockBatchId()); line.setSourceStorageLocationId(firstNonNull(input.getStorageLocationId(), request.getSourceLocationId()));
            line.setDestinationStorageLocationId(request.getDestinationLocationId());
            transferPostingService.validatePlanSource(source.getId(), material.getId(), input.getStockBatchId(), line.getSourceStorageLocationId());
            lineRepository.save(line);
        }
        return savedResponse(entity, "EDIT", "Edit draft transfer");
    }
    @Transactional
    public TransferDocumentResponse returnForCorrection(UUID id, TransferShipmentDtos.Action request) {
        var entity = actionPolicy.require(id, Action.RETURN_FOR_CORRECTION);
        requireActionRequest(entity, request); requireNoShipmentActivity(entity); entity.setStatus("DRAFT");
        return savedResponse(entity, "RETURN_FOR_CORRECTION", request.reason().trim());
    }
    @Transactional
    public TransferDocumentResponse cancelRemainder(UUID id, TransferShipmentDtos.Action request, String key) {
        requireShipmentFlow(); var entity = actionPolicy.lockAuthorized(id, Action.CANCEL_REMAINDER);
        if (request == null || blank(key)) throw new BadRequestException("Version, cancellation reason and Idempotency-Key are required");
        return maskFinancials(idempotencyService.execute("TRANSFER_CANCEL_REMAINDER", id, key, request, TransferDocumentResponse.class, () -> {
            actionPolicy.requireState(entity, Action.CANCEL_REMAINDER); requireActionRequest(entity, request); requireReconciled(entity);
            boolean changed = false;
            for (var line : lines(id)) {
                var remaining = line.getPlannedQuantity().subtract(line.getDispatchedQuantity()).subtract(line.getCancelledQuantity());
                if (remaining.signum() > 0) {
                    line.setCancelledQuantity(line.getCancelledQuantity().add(remaining));
                    line.setDescription("Undispatched remainder cancelled: " + request.reason().trim()); lineRepository.save(line); changed = true;
                }
            }
            if (!changed) throw new ConflictException("There is no undispatched remainder to cancel");
            refreshShipmentProjection(entity); if (allocations(id).isEmpty()) entity.setStatus("CANCELLED");
            return savedResponse(entity, "CANCEL_REMAINDER", request.reason().trim());
        }));
    }
    private void requireActionRequest(TransferDocument entity, TransferShipmentDtos.Action request) {
        requireExpectedVersion(entity, request == null ? null : request.expectedVersion());
        if (blank(request.reason())) throw new BadRequestException("Explain the reason for this change");
    }
    private void requireNoShipmentActivity(TransferDocument entity) {
        if ("RECONCILIATION_REQUIRED".equals(entity.getReconciliationStatus()) || !allocations(entity.getId()).isEmpty() || entity.getDispatchMovementDocumentId()!=null || entity.getReceiveMovementDocumentId()!=null || lines(entity.getId()).stream().anyMatch(l -> nvl(l.getDispatchedQuantity()).signum()!=0 || nvl(l.getReceivedQuantity()).signum()!=0 || nvl(l.getInTransitQuantity()).signum()!=0 || nvl(l.getDamagedQuantity()).signum()!=0 || nvl(l.getMissingQuantity()).signum()!=0))
            throw new ConflictException("Posted shipment history cannot be rewritten");
    }
    private void requireReconciled(TransferDocument entity) {
        if (progress.describe(entity).progress().closeBlockers().contains("LEGACY_RECONCILIATION_REQUIRED"))
            throw new BusinessException("Reconcile historical shipment evidence before continuing", "LEGACY_RECONCILIATION_REQUIRED");
    }

    @Transactional(readOnly = true)
    public com.enterprise.mm.payload.TransferPickingDtos.Context dispatchContext(UUID id) {
        var transfer = pickingTransfer(id);
        return stockPicking.context(transfer, lines(id));
    }

    @Transactional(readOnly = true)
    public com.enterprise.mm.payload.TransferPickingDtos.StockOptions stockOptions(UUID id, UUID lineId,
        com.enterprise.mm.enums.PickingMethod method, int page, int size) {
        var transfer = pickingTransfer(id);
        var line = lines(id).stream().filter(l -> l.getId().equals(lineId)).findFirst()
            .orElseThrow(() -> new BadRequestException("transferLineId does not belong to transfer"));
        return stockPicking.options(transfer, line, method, page, size);
    }

    @Transactional(readOnly = true)
    public com.enterprise.mm.payload.TransferPickingDtos.Preview dispatchPreview(UUID id, TransferDispatchRequest request) {
        var transfer = pickingTransfer(id);
        if (request == null || request.getDispatchedDate() == null)
            throw new BadRequestException("dispatchedDate is required");
        requireExpectedVersion(transfer, request.getExpectedVersion());
        var plan = stockPicking.allocate(transfer, lines(id), request, false);
        return new com.enterprise.mm.payload.TransferPickingDtos.Preview(id, transfer.getVersion(), LocalDateTime.now(), plan.decisions());
    }

    private TransferDocument pickingTransfer(UUID id) {
        requireShipmentFlow();
        var transfer = findScopedEntity(id);
        actionPolicy.requireAuthority(transfer, Action.DISPATCH);
        actionPolicy.requireState(transfer, Action.DISPATCH);
        requireReconciled(transfer);
        return transfer;
    }

    @Transactional
    public TransferDocumentResponse dispatch(UUID id, TransferDispatchRequest request, String idempotencyKey) {
        requireShipmentFlow();
        var entity = actionPolicy.lockAuthorized(id, Action.DISPATCH);
        requireEnvelope(request == null ? null : request.getExpectedVersion(), request == null ? null : request.getDispatchedDate(),
            request == null ? null : request.getLines(), idempotencyKey);
        try {
            var result = idempotencyService.execute("TRANSFER_DISPATCH", id, idempotencyKey, request, TransferDocumentResponse.class, () -> {
                actionPolicy.requireState(entity, Action.DISPATCH); requireExpectedVersion(entity, request.getExpectedVersion()); requireReconciled(entity);
                var documentLines = lines(id);
                var selected = byId(documentLines);
                var picking = stockPicking.allocate(entity, documentLines, request, true);
                var shipment = new TransferShipment(); shipment.setName("Transfer dispatch for " + BusinessDisplayNamePolicy.requireHumanName("TransferDocument", entity.getName(), entity.getCode()));
                shipment.setTransferDocumentId(id); shipment.setDispatchedDate(request.getDispatchedDate()); shipment.setOperationKey(idempotencyKey);
                shipment.setPickingDecisions(picking.decisions());
                shipment = shipmentRepository.saveAndFlush(shipment);
                // Every command locks stock keys in the same order, including commands containing multiple materials.
                var ordered = picking.postings().stream().sorted(java.util.Comparator.comparing((TransferDispatchRequest.Line input) ->
                    selected.get(input.getTransferLineId()).getMaterialId().toString() + ":" + input.getStockBatchId() + ":" + input.getStorageLocationId() + ":" + input.getStorageBinId())).toList();
                List<ErpExchangeService.Line> exchangeLines = new ArrayList<>();
                for (var input : ordered) {
                    var line = selected.get(input.getTransferLineId());
                    var material = requireMaterial(line.getMaterialId());
                    var movement = transferMovement(entity.getSourceWarehouseId(), line.getMaterialId(), input.getStockBatchId(),
                        input.getStorageLocationId(), input.getStorageBinId(), input.getDispatchedQuantity(), request.getDispatchedDate());
                    var posted = transferPostingService.post(new TransferPostingContext(id, shipment.getId(), null, TransferPostingContext.Kind.DISPATCH, line.getId()), movement);
                    if (erpExchange != null && erpExchange.captureEnabled()) exchangeLines.add(new ErpExchangeService.Line(line.getMaterialId(), entity.getSourceWarehouseId(), posted.quantity(), posted.bookValue()));
                    if (material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED) {
                        List<MaterialUnit> dispatchedUnits = materialUnitEligibilityService.dispatchTransferExact(
                            line.getMaterialId(),
                            entity.getSourceWarehouseId(),
                            input.getStorageLocationId(),
                            input.getStorageBinId(),
                            input.getDispatchedQuantity(),
                            input.getMaterialUnitIds(),
                            ENTITY_NAME,
                            entity.getId(),
                            line.getId()
                        );
                        validateUnitBatch(dispatchedUnits, input.getStockBatchId());
                    }
                }
                entity.setDispatchedDate(request.getDispatchedDate()); refreshShipmentProjection(entity);
                var response = savedResponse(entity, "DISPATCH", "Dispatch selected source allocations");
                if (!exchangeLines.isEmpty()) ErpExchangeHooks.accept(erpExchange, "TRANSFER", entity.getId(), entity.getVersion(), request.getDispatchedDate(),
                    "TRANSFER_DISPATCH", entity.getSourceWarehouseId(), entity.getDestinationWarehouseId(), exchangeLines);
                return response;
            });
            return maskFinancials(result);
        } catch (org.springframework.dao.PessimisticLockingFailureException busy) {
            throw new ConflictException("Source stock is being changed by another operation; refresh and retry", "stockPicking.busy");
        }
    }

    @Transactional
    public TransferDocumentResponse receive(UUID id, TransferReceiveRequest request, String idempotencyKey) {
        try {
            requireShipmentFlow();
            var entity = actionPolicy.lockAuthorized(id, Action.RECEIVE);
            requireEnvelope(request == null ? null : request.getExpectedVersion(), request == null ? null : request.getReceivedDate(),
                request == null ? null : request.getLines(), idempotencyKey);
            var result = idempotencyService.execute("TRANSFER_RECEIVE", id, idempotencyKey, request, TransferDocumentResponse.class, () -> {
                actionPolicy.requireState(entity, Action.RECEIVE);
                if (!Objects.equals(entity.getVersion(), request.getExpectedVersion()))
                    throw new com.enterprise.mm.error.TransferReceiptConflictException(entity.getVersion());
                requireReconciled(entity);
                if (entity.getReturnOfTransferId() != null) throw new BusinessException("Confirm the return through its original exception case");
                boolean exchangeEnabled = erpExchange != null && erpExchange.captureEnabled();
                for (var line : request.getLines()) ErpExchangeHooks.requireSupportedReceipt(exchangeEnabled, line.getDamagedQuantity());
                var prepared = receipts.prepare(entity, request, allocations(id));
                var ordered = prepared.stream().sorted(java.util.Comparator.comparing(e -> e.allocation().getId().toString()
                    + ":" + e.input().getStorageLocationId() + ":" + e.input().getStorageBinId())).toList();
                List<ErpExchangeService.Line> exchangeLines = new ArrayList<>();
                for (var entry : ordered) {
                    var input = entry.input(); var allocation = entry.allocation();
                    Material material = requireMaterial(allocation.getMaterialId());
                    boolean serialized = material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED;
                    if (input.getReceivedQuantity().signum() > 0) {
                        var posted = postReceipt(entity, allocation, input, input.getReceivedQuantity(), request.getReceivedDate(), TransferPostingContext.Kind.GOOD_RECEIPT);
                        if (erpExchange != null && erpExchange.captureEnabled()) exchangeLines.add(new ErpExchangeService.Line(allocation.getMaterialId(), entity.getDestinationWarehouseId(), posted.quantity(), posted.bookValue()));
                        if (serialized) receivePhysicalUnits(entity, allocation, input, input.getReceivedQuantity(), input.getMaterialUnitIds(), posted.destinationStockBatchId(), false);
                    }
                    if (input.getDamagedQuantity().signum() > 0) {
                        var posted = postReceipt(entity, allocation, input, input.getDamagedQuantity(), request.getReceivedDate(), TransferPostingContext.Kind.QUARANTINE_RECEIPT);
                        if (erpExchange != null && erpExchange.captureEnabled()) exchangeLines.add(new ErpExchangeService.Line(allocation.getMaterialId(), entity.getDestinationWarehouseId(), posted.quantity(), posted.bookValue()));
                        if (serialized) receivePhysicalUnits(entity, allocation, input, input.getDamagedQuantity(), input.getDamagedMaterialUnitIds(), posted.destinationStockBatchId(), true);
                    }
                }
                entity.setReceivedDate(request.getReceivedDate()); refreshShipmentProjection(entity);
                var response = savedResponse(entity, "RECEIVE", "Receive selected shipment allocations");
                if (!exchangeLines.isEmpty()) ErpExchangeHooks.accept(erpExchange, "TRANSFER", entity.getId(), entity.getVersion(), request.getReceivedDate(),
                    "TRANSFER_RECEIPT", entity.getSourceWarehouseId(), entity.getDestinationWarehouseId(), exchangeLines);
                return response;
            });
            return maskFinancials(result);
        } catch (org.springframework.dao.PessimisticLockingFailureException busy) {
            throw new ConflictException("Transfer receipt is being changed by another operation; refresh and retry", "transfers.receipt.busy");
        }
    }

    private TransferPostingService.PostedTransferStock postReceipt(TransferDocument transfer, TransferShipmentAllocation allocation, TransferReceiveRequest.Line input,
        BigDecimal quantity, LocalDate date, TransferPostingContext.Kind kind) {
        var movement = transferMovement(transfer.getDestinationWarehouseId(), allocation.getMaterialId(), null,
            input.getStorageLocationId(), input.getStorageBinId(), quantity, date);
        movement.setDescription(firstNonBlank(input.getConditionEvidence(), input.getShortageReason()));
        return transferPostingService.postWithEvidence(new TransferPostingContext(transfer.getId(), allocation.getShipmentId(), allocation.getId(), kind), movement, input.getAttachmentIds());
    }
    private void receivePhysicalUnits(TransferDocument transfer, TransferShipmentAllocation allocation, TransferReceiveRequest.Line input,
        BigDecimal quantity, List<UUID> ids, UUID destinationBatchId, boolean quarantine) {
        validateTransferUnitsBelongToDocument(transfer.getId(), ids);
        materialUnitEligibilityService.receiveTransferWithDisposition(allocation.getMaterialId(), allocation.getSourceWarehouseId(),
            allocation.getSourceStockBatchId(), allocation.getSerialNumber(), transfer.getDestinationWarehouseId(),
            input.getStorageLocationId(), input.getStorageBinId(), destinationBatchId, quantity, ids, quarantine,
            ENTITY_NAME, transfer.getId(), allocation.getTransferLineId());
    }
    private StockMovement transferMovement(UUID warehouse, UUID material, UUID batch, UUID location, UUID bin, BigDecimal quantity, LocalDate date) {
        var movement = new StockMovement(); movement.setWarehouseId(warehouse); movement.setMaterialId(material); movement.setStockBatchId(batch);
        movement.setStorageLocationId(location); movement.setStorageBinId(bin); movement.setQuantity(quantity); movement.setMovementDate(date); return movement;
    }
    private List<TransferShipmentAllocation> allocations(UUID id) {
        return allocationRepository.findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc(TenantContext.currentTenant(), CompanyContext.currentCompany(), id);
    }
    private void refreshShipmentProjection(TransferDocument entity) { progress.refresh(entity); }
    private List<ErpExchangeService.Line> transferExchangeLines(List<?> inputs, List<TransferDocumentLine> documentLines,
                                                                UUID warehouseId, boolean dispatch) {
        Map<UUID, TransferDocumentLine> byId = new HashMap<>();
        for (TransferDocumentLine line : documentLines) {
            byId.put(line.getId(), line);
        }
        List<ErpExchangeService.Line> lines = new ArrayList<>();
        if (inputs == null) {
            return lines;
        }
        for (Object input : inputs) {
            UUID lineId;
            BigDecimal quantity;
            if (dispatch) {
                var row = (TransferDispatchRequest.Line) input;
                lineId = row.getTransferLineId();
                quantity = row.getDispatchedQuantity();
            } else {
                var row = (TransferReceiveRequest.Line) input;
                lineId = row.getTransferLineId();
                quantity = row.getReceivedQuantity();
            }
            TransferDocumentLine line = byId.get(lineId);
            if (line == null) {
                continue;
            }
            lines.add(new ErpExchangeService.Line(line.getMaterialId(), warehouseId, quantity, BigDecimal.ZERO));
        }
        return lines;
    }

    private TransferDocumentResponse savedResponse(TransferDocument entity, String action, String reason) {
        entity.setUpdatedAt(java.time.LocalDateTime.now()); repository.saveAndFlush(entity);
        var response = response(entity); auditService.logInCurrentTransaction(ENTITY_NAME, entity.getId(), action, reason, null, null); return response;
    }
    private void requireShipmentFlow() {
        if (!shipmentFlowEnabled) throw new ConflictException("Shipment operations are not enabled yet", "SHIPMENT_FLOW_DISABLED");
    }
    private void requireEnvelope(Long version, LocalDate date, List<?> lines, String key) {
        if (version == null || date == null || lines == null || lines.isEmpty() || blank(key))
            throw new BadRequestException("Version, actual posting date, selected rows and Idempotency-Key are required");
        if (key.length() > 160) throw new BadRequestException("Idempotency-Key must not exceed 160 characters");
    }
    private void requireQuantity(BigDecimal quantity, boolean allowZero) {
        if (quantity == null || quantity.signum() < 0 || (!allowZero && quantity.signum() == 0)
            || quantity.stripTrailingZeros().scale() > StockUomPolicy.MAX_QUANTITY_SCALE || quantity.precision() - quantity.scale() > 15)
            throw new BadRequestException("Quantity must be positive and fit 15 digits with at most seven decimal places");
    }

    private void requireQuantityScale(BigDecimal quantity, int scale) {
        if (quantity.stripTrailingZeros().scale() > scale)
            throw new BadRequestException("Quantity exceeds the shipment unit's allowed decimal precision");
    }

    private void validateUnitBatch(List<MaterialUnit> units, UUID expectedBatchId) {
        if (units == null) {
            return;
        }
        boolean mismatch = units.stream().anyMatch(unit -> !Objects.equals(unit.getStockBatchId(), expectedBatchId));
        if (mismatch) {
            throw new BadRequestException("MATERIAL_UNIT_WRONG_BATCH");
        }
    }

    private void validateTransferUnitsBelongToDocument(UUID transferId, List<UUID> materialUnitIds) {
        if (materialUnitIds == null || materialUnitIds.isEmpty()) {
            throw new BusinessException("SERIAL_UNIT_SELECTION_REQUIRED");
        }
        var dispatched = new java.util.LinkedHashSet<>(materialUnitEligibilityService.operationUnitIds(
            ENTITY_NAME,
            transferId,
            MaterialUnitOperationType.TRANSFER
        ));
        if (!dispatched.containsAll(materialUnitIds)) {
            throw new BadRequestException("MATERIAL_UNIT_NOT_AVAILABLE");
        }
    }

    @Transactional
    public TransferDocumentResponse recordDiscrepancy(UUID id, TransferDiscrepancyRequest request) {
        if (shipmentFlowEnabled) throw new BusinessException("Report missing stock against its shipment allocation; receive damaged stock into quarantine", "SHIPMENT_SELECTION_REQUIRED");
        TransferDocument entity = findEntity(id);
        if (!List.of(TransferStatus.IN_TRANSIT.name(), TransferStatus.RECEIVED.name(), TransferStatus.DISCREPANCY_REVIEW.name()).contains(entity.getStatus())) {
            throw new ConflictException("Discrepancy cannot be recorded in status " + entity.getStatus());
        }
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new BadRequestException("Discrepancy lines are required");
        }
        requireExpectedVersion(entity, request.expectedVersion());
        String oldSnapshot = snapshotSerializer.toJson(response(entity));
        Map<UUID, TransferDocumentLine> existing = byId(lines(id));
        for (TransferDiscrepancyRequest.Line input : request.lines()) {
            TransferDocumentLine line = existing.get(input.transferLineId());
            if (line == null) {
                throw new BadRequestException("transferLineId does not belong to transfer");
            }
            BigDecimal oldDiscrepancy = nvl(line.getDamagedQuantity()).add(nvl(line.getMissingQuantity()));
            BigDecimal damaged = nvl(input.damagedQuantity());
            BigDecimal missing = nvl(input.missingQuantity());
            if (damaged.add(missing).signum() > 0
                && (isBlank(input.reason()) || isBlank(input.evidence()))) {
                throw new BadRequestException("Discrepancy reason and evidence are required for damaged or missing quantity");
            }
            BigDecimal nextInTransit = nvl(line.getInTransitQuantity()).add(oldDiscrepancy).subtract(damaged.add(missing));
            if (damaged.signum() < 0 || missing.signum() < 0 || nextInTransit.signum() < 0) {
                throw new BadRequestException("Discrepancy quantities exceed unresolved transfer quantity");
            }
            line.setDamagedQuantity(damaged);
            line.setMissingQuantity(missing);
            line.setInTransitQuantity(nextInTransit);
            line.setDiscrepancyReason(input.reason());
            line.setDiscrepancyEvidence(input.evidence());
            lineRepository.save(line);
        }
        entity.setStatus(TransferStatus.DISCREPANCY_REVIEW.name());
        TransferDocument saved = repository.saveAndFlush(entity);
        TransferDocumentResponse response = response(saved);
        auditService.log(ENTITY_NAME, id, "RECORD_DISCREPANCY", saved.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional
    public TransferDocumentResponse close(UUID id, com.enterprise.mm.payload.TransferShipmentDtos.Action request) {
        var entity = actionPolicy.lockAuthorized(id, Action.CLOSE);
        requireExpectedVersion(entity, request == null ? null : request.expectedVersion());
        actionPolicy.requireState(entity, Action.CLOSE);
        var readiness = progress.get(id);
        if (!readiness.canClose()) throw new ConflictException("Transfer has unresolved work: " + String.join(", ", readiness.closeBlockers()));
        entity.setStatus(TransferStatus.CLOSED.name());
        return savedResponse(entity, "CLOSE", request.reason());
    }

    @Transactional
    public TransferDocumentResponse cancel(UUID id, com.enterprise.mm.payload.TransferShipmentDtos.Action request) {
        var entity = actionPolicy.require(id, Action.CANCEL);
        requireActionRequest(entity, request);
        if (!allocations(id).isEmpty() || lines(id).stream().anyMatch(l -> l.getDispatchedQuantity().signum() > 0))
            throw new ConflictException("Dispatched transfers require explicit remainder cancellation and physical resolution");
        for (var line : lines(id)) {
            line.setCancelledQuantity(line.getPlannedQuantity());
            lineRepository.save(line);
        }
        entity.setStatus(TransferStatus.CANCELLED.name());
        return savedResponse(entity, "CANCEL", request.reason());
    }

    private Map<UUID, TransferDocumentLine> byId(List<TransferDocumentLine> lines) {
        Map<UUID, TransferDocumentLine> result = new HashMap<>();
        lines.forEach(line -> result.put(line.getId(), line));
        return result;
    }

    private TransferDocument findEntity(UUID id) {
        TransferDocument entity = findScopedEntity(id);
        if (!roleDataScopeService.canAccessWarehouse(entity.getSourceWarehouseId(), entity.getDestinationWarehouseId())) {
            throw new NotFoundException("Transfer document not found: " + id);
        }
        return entity;
    }

    private TransferDocument findScopedEntity(UUID id) {
        TransferDocument entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Transfer document not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || !CompanyContext.currentCompany().equals(entity.getCompanyId())) {
            throw new NotFoundException("Transfer document not found: " + id);
        }
        return entity;
    }

    private List<TransferDocumentLine> lines(UUID id) {
        return lineRepository.findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), id
        );
    }

    private TransferDocumentResponse response(TransferDocument entity) {
        var response = mapper.toResponse(entity, lines(entity.getId()));
        response.setShipmentFlowEnabled(shipmentFlowEnabled); response.setReconciliationStatus(entity.getReconciliationStatus());
        var projection = progress.describe(entity);
        response.setProgress(projection.progress()); response.setUnitSummaries(projection.units());
        boolean exactValue = Set.of("NOT_REQUIRED", "RECONCILED").contains(entity.getReconciliationStatus())
            && projection.lines().values().stream().allMatch(q -> !q.legacy() && q.transitValue() != null);
        response.setInTransitAmount(exactValue ? projection.lines().values().stream()
            .map(TransferProgressService.LineQuantities::transitValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add) : null);
        response.setBookCurrencyCode("UZS");
        response.setReturnOfTransferId(entity.getReturnOfTransferId()); response.setReturnExceptionId(projection.returnExceptionId());
        for (var line : response.getLines()) {
            var quantities = projection.lines().get(line.getId());
            if (quantities != null && quantities.legacy()) { line.setInTransitValue(null); line.setUnitCost(null); }
            if (quantities != null && !quantities.legacy()) {
                var state = quantities.state(); line.setDispatchedQuantity(state.dispatched()); line.setInTransitQuantity(state.unresolvedTransit());
                line.setReceivedQuantity(state.received()); line.setRemainingQuantity(state.stillToDispatch()); line.setReturnedQuantity(state.returnedFromTransit());
                line.setPostedLossQuantity(state.postedTransitLoss()); line.setDamagedQuantity(quantities.damaged()); line.setMissingQuantity(quantities.missing());
                line.setInTransitValue(quantities.transitValue());
            }
        }
        var allocations = allocations(entity.getId());
        var balances = allocations.stream().collect(java.util.stream.Collectors.toMap(TransferShipmentAllocation::getId, a -> transitLedger.balance(a.getId())));
        response.setShipments(shipmentRepository.findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc(entity.getTenantId(), entity.getCompanyId(), entity.getId()).stream()
            .map(shipment -> new TransferShipmentDtos.Shipment(shipment.getId(), shipment.getDispatchedDate(), allocations.stream()
                .filter(a -> a.getShipmentId().equals(shipment.getId())).map(a -> {
                    var balance = balances.get(a.getId());
                    var history = clearanceRepository.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(entity.getTenantId(), entity.getCompanyId(), a.getId()).stream()
                        .map(c -> new TransferShipmentDtos.Clearance(c.getId(), c.getKind().name(), c.getQuantity(), c.getBookValue(), c.getPostingDate(), c.getMovementId(),
                            c.getDestinationStockBatchId(), c.getCreatedBy(), progress.actorDisplayName(c.getCreatedBy()), c.getCreatedAt())).toList();
                    return new TransferShipmentDtos.Allocation(a.getId(), a.getTransferLineId(), a.getSourceStockBatchId(), a.getBatchNumber(), a.getSerialNumber(), a.getExpiryDate(),
                        a.getDispatchedQuantity(), balance.quantity(), balance.bookValue(), a.getUnitCode(), a.getSerialNumber() == null || a.getSerialNumber().isBlank() ? a.getQuantityScale() : 0, history).withUnitShortName(unitDisplay.shortName(a, a.getUnitOfMeasureId(), a.getUnitCode()));
                }).toList(), shipment.getCreatedBy(), progress.actorDisplayName(shipment.getCreatedBy()), shipment.getCreatedAt(), shipment.getPickingDecisions())).toList());
        response.setAllowedActions(java.util.Arrays.stream(Action.values()).filter(a -> actionPolicy.canAct(entity, a))
            .filter(a -> shipmentFlowEnabled || (a != Action.DISPATCH && a != Action.RECEIVE && a != Action.CANCEL_REMAINDER))
            .filter(a -> a != Action.DISPATCH || projection.progress().canDispatch())
            .filter(a -> a != Action.RECEIVE || projection.progress().canReceive())
            .filter(a -> a != Action.CLOSE || projection.progress().canClose())
            .filter(a -> a != Action.CANCEL_REMAINDER || (!projection.progress().closeBlockers().contains("LEGACY_RECONCILIATION_REQUIRED") && projection.units().stream().anyMatch(u -> u.remaining().signum() > 0)))
            .map(a -> a.name().toLowerCase(Locale.ROOT).replace('_', '-')).toList());
        return maskFinancials(response);
    }
    private TransferDocumentResponse maskFinancials(TransferDocumentResponse response) {
        if (!TransferActionPolicy.hasRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT", "WAREHOUSE_HEAD", "WAREHOUSE_MANAGER")) {
            response.setInTransitAmount(null);
        }
        if (TransferActionPolicy.hasRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")) return response;
        response.getLines().forEach(line -> { line.setUnitCost(null); line.setInTransitValue(null); });
        if (response.getShipments() != null) response.setShipments(response.getShipments().stream().map(s -> new TransferShipmentDtos.Shipment(s.id(), s.dispatchedDate(),
            s.allocations().stream().map(a -> new TransferShipmentDtos.Allocation(a.id(), a.transferLineId(), a.sourceStockBatchId(), a.batchNumber(), a.serialNumber(), a.expiryDate(),
                a.dispatchedQuantity(), a.unresolvedQuantity(), null, a.unitCode(), a.quantityScale(),
                a.clearances() == null ? List.of() : a.clearances().stream().map(c -> new TransferShipmentDtos.Clearance(c.id(), c.kind(), c.quantity(), null, c.postingDate(), c.movementId(), c.destinationStockBatchId(), c.actor(), c.actorDisplayName(), c.createdAt())).toList()).withUnitShortName(a.unitShortName()))
                .toList(), s.actor(), s.actorDisplayName(), s.createdAt(), s.pickingDecisions())).toList());
        return response;
    }

    private Warehouse requireWarehouse(UUID id) {
        if (id == null) {
            throw new BadRequestException("warehouseId is required");
        }
        return warehouseRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId())
                && CompanyContext.currentCompany().equals(entity.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Warehouse not found: " + id));
    }

    private Material requireMaterial(UUID id) {
        Material material = materialRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId())
                && CompanyContext.currentCompany().equals(entity.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Material not found: " + id));
        if (material.getMaterialType() != MaterialType.TMB) {
            throw new BusinessException(
                "Transfer stock lines require TMB material: " + id,
                material.getMaterialType() == MaterialType.FIXED_ASSET ? "FIXED_ASSET_STOCK_FORBIDDEN" : "TMB_STOCK_ONLY",
                "materialId",
                material.getMaterialType()
            );
        }
        return material;
    }

    private void requireExpectedVersion(TransferDocument entity, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new BadRequestException("expectedVersion is required");
        }
        if (!Objects.equals(expectedVersion, entity.getVersion())) {
            throw new ConflictException(
                "Transfer version conflict: expected=" + expectedVersion + ", actual=" + entity.getVersion()
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Specification<TransferDocument> spec(TransferSearchRequest request) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            predicates.add(cb.isFalse(root.get("isDeleted")));
            if (request != null) {
                if (request.search() != null && !request.search().isBlank()) {
                    String pattern = "%" + request.search().trim().toLowerCase(Locale.ROOT) + "%";
                    predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), pattern),
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern)
                    ));
                }
                if (request.status() != null && !request.status().isEmpty()) {
                    predicates.add(root.get("status").in(request.status()));
                }
                if ("dispatch".equals(request.action()) || "receive".equals(request.action())) {
                    var subquery = query.subquery(UUID.class);
                    var line = subquery.from(TransferDocumentLine.class);
                    var remaining = "receive".equals(request.action()) ? cb.greaterThan(line.<BigDecimal>get("inTransitQuantity"), BigDecimal.ZERO)
                        : cb.greaterThan(line.<BigDecimal>get("plannedQuantity"), cb.sum(line.<BigDecimal>get("dispatchedQuantity"), line.<BigDecimal>get("cancelledQuantity")));
                    subquery.select(line.get("id")).where(cb.equal(line.get("transferDocumentId"), root.get("id")), cb.isFalse(line.get("isDeleted")), remaining);
                    predicates.add(cb.exists(subquery));
                    predicates.add(root.get("status").in("receive".equals(request.action()) ? List.of("IN_TRANSIT", "DISCREPANCY_REVIEW") : List.of("APPROVED", "IN_TRANSIT", "DISCREPANCY_REVIEW")));
                    if ("receive".equals(request.action())) predicates.add(cb.isNull(root.get("returnOfTransferId")));
                }
                if (request.warehouseId() != null) {
                    Predicate source = cb.equal(root.get("sourceWarehouseId"), request.warehouseId());
                    Predicate destination = cb.equal(root.get("destinationWarehouseId"), request.warehouseId());
                    if ("dispatch".equals(request.action())) {
                        predicates.add(source);
                    } else if ("receive".equals(request.action())) {
                        predicates.add(destination);
                    } else if ("tasks".equals(request.action())) {
                        var transit = query.subquery(UUID.class);
                        var line = transit.from(TransferDocumentLine.class);
                        transit.select(line.get("id")).where(
                            cb.equal(line.get("transferDocumentId"), root.get("id")), cb.isFalse(line.get("isDeleted")),
                            cb.greaterThan(line.<BigDecimal>get("inTransitQuantity"), BigDecimal.ZERO));
                        predicates.add(cb.or(
                            cb.and(source, root.get("status").in("DRAFT", "SUBMITTED", "APPROVED", "IN_TRANSIT", "DISCREPANCY_REVIEW", "RECEIVED")),
                            cb.and(destination, root.get("status").in("IN_TRANSIT", "DISCREPANCY_REVIEW"),
                                cb.exists(transit), cb.isNull(root.get("returnOfTransferId")))
                        ));
                    } else {
                        predicates.add(cb.or(source, cb.and(destination,
                            cb.not(root.get("status").in("DRAFT", "SUBMITTED", "APPROVED")))));
                    }
                }
                if (request.sourceWarehouseId() != null) {
                    predicates.add(cb.equal(root.get("sourceWarehouseId"), request.sourceWarehouseId()));
                }
                if (request.destinationWarehouseId() != null) {
                    predicates.add(cb.equal(root.get("destinationWarehouseId"), request.destinationWarehouseId()));
                }
                if (request.dateFrom() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("plannedDate"), request.dateFrom()));
                }
                if (request.dateTo() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("plannedDate"), request.dateTo()));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return null;
    }

    private UUID firstNonNull(UUID first, UUID second) {
        return first == null ? second : first;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    private String normalized(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

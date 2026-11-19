package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IssueRequest;
import com.enterprise.mm.entity.IssueRequestLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.erp.ErpExchangeHooks;
import com.enterprise.mm.erp.ErpExchangeService;
import com.enterprise.mm.enums.IssueRequestCostObjectType;
import com.enterprise.mm.enums.IssueRequestStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialReservationType;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.IssueRequestMapper;
import com.enterprise.mm.payload.IssueRequestApproveRequest;
import com.enterprise.mm.payload.IssueRequestCreateRequest;
import com.enterprise.mm.payload.IssueRequestIssueRequest;
import com.enterprise.mm.payload.IssueRequestReserveRequest;
import com.enterprise.mm.payload.IssueRequestResponse;
import com.enterprise.mm.payload.IssueRequestSearchRequest;
import com.enterprise.mm.payload.MaterialReservationCreateRequest;
import com.enterprise.mm.payload.MaterialReservationResponse;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.ConsumeRequest;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentItemRequest;
import com.enterprise.mm.payload.StockMovementStatusChangeRequest;
import com.enterprise.mm.payload.StockMovementPostRequest;
import com.enterprise.mm.repository.IssueRequestLineRepository;
import com.enterprise.mm.repository.IssueRequestRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IssueRequestService {
    private static final String ENTITY_NAME = "IssueRequest";

    private final IssueRequestRepository repository;
    private final IssueRequestLineRepository lineRepository;
    private final IssueRequestMapper mapper;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final MaterialRepository materialRepository;
    private final MaterialReservationService materialReservationService;
    private final StockMovementDocumentService stockMovementDocumentService;
    private final StockBalanceRepository stockBalanceRepository;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;
    private final RoleDataScopeService roleDataScopeService;
    private final MaterialUnitEligibilityService materialUnitEligibilityService;
    private final com.enterprise.mm.repository.DemandPlanItemRepository demandPlanItemRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ErpExchangeService erpExchange;

    public IssueRequestService(
        IssueRequestRepository repository,
        IssueRequestLineRepository lineRepository,
        IssueRequestMapper mapper,
        WarehouseRepository warehouseRepository,
        UserRepository userRepository,
        MaterialRepository materialRepository,
        MaterialReservationService materialReservationService,
        StockMovementDocumentService stockMovementDocumentService,
        StockBalanceRepository stockBalanceRepository,
        IdempotencyService idempotencyService,
        AuditService auditService,
        AuditSnapshotSerializer snapshotSerializer,
        RoleDataScopeService roleDataScopeService,
        MaterialUnitEligibilityService materialUnitEligibilityService,
        com.enterprise.mm.repository.DemandPlanItemRepository demandPlanItemRepository
    ) {
        this.repository = repository;
        this.lineRepository = lineRepository;
        this.mapper = mapper;
        this.warehouseRepository = warehouseRepository;
        this.userRepository = userRepository;
        this.materialRepository = materialRepository;
        this.materialReservationService = materialReservationService;
        this.stockMovementDocumentService = stockMovementDocumentService;
        this.stockBalanceRepository = stockBalanceRepository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.snapshotSerializer = snapshotSerializer;
        this.roleDataScopeService = roleDataScopeService;
        this.materialUnitEligibilityService = materialUnitEligibilityService;
        this.demandPlanItemRepository = demandPlanItemRepository;
    }

    @Transactional(readOnly = true)
    public Page<IssueRequestResponse> search(IssueRequestSearchRequest request, Pageable pageable) {
        IssueRequestSearchRequest effective = request == null ? IssueRequestSearchRequest.empty() : request;
        effective = new IssueRequestSearchRequest(
            effective.search(),
            effective.status(),
            effective.warehouseId(),
            effective.requesterUserId(),
            effective.costObjectType(),
            effective.costObjectId(),
            effective.dateFrom(),
            effective.dateTo(),
            effective.includeDeleted()
        );
        return repository.findAll(spec(effective), pageable)
            .map(entity -> response(entity, lines(entity.getId())));
    }

    @Transactional(readOnly = true)
    public List<com.enterprise.mm.payload.StockBalanceResponse> stockPositions(UUID id, UUID lineId) {
        IssueRequest document = findEntity(id);
        IssueRequestLine line = lines(id).stream().filter(l -> l.getId().equals(lineId)).findFirst()
            .orElseThrow(() -> new NotFoundException("Issue line is unavailable"));
        var reservation = line.getReservationId() == null ? null : materialReservationService.get(line.getReservationId());
        return stockBalanceRepository.findByTenantIdAndCompanyIdAndWarehouseIdAndMaterialIdInAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), document.getWarehouseId(), List.of(line.getMaterialId()))
            .stream().map(balance -> {
                var dto = new com.enterprise.mm.mapper.StockBalanceMapper().toResponse(balance);
                boolean reservedPosition = reservation != null && Objects.equals(reservation.getStockBatchId(), balance.getStockBatchId())
                    && Objects.equals(reservation.getStorageLocationId(), balance.getStorageLocationId())
                    && Objects.equals(reservation.getStorageBinId(), balance.getStorageBinId());
                BigDecimal executable = nvl(balance.getAvailableQuantity()).add(reservedPosition ? nvl(reservation.getReservedQuantity()) : BigDecimal.ZERO);
                dto.setExecutableQuantity(executable);
                dto.setUnavailableReason(reservation != null && !reservedPosition ? "reservation" : StockPositionPolicy.unavailableReason(balance, executable));
                return dto;
            }).toList();
    }

    @Transactional(readOnly = true)
    public IssueRequestResponse get(UUID id) {
        IssueRequest entity = findEntity(id);
        return response(entity, lines(id));
    }

    @Transactional(readOnly = true)
    public Page<com.enterprise.mm.payload.StockMovementDocumentResponse> issueDocuments(UUID id, Pageable pageable) {
        IssueRequest entity = findEntity(id);
        return stockMovementDocumentService.search(new com.enterprise.mm.payload.StockMovementDocumentSearchRequest(
            null, null, entity.getWarehouseId(), null, null, false, entity.getId(), MovementType.ISSUE
        ), pageable);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public IssueRequestResponse create(IssueRequestCreateRequest request) {
        if (request == null) {
            throw new BadRequestException("Issue request body is required");
        }
        validateCreateRequest(request, null);
        IssueRequest entity = mapper.toEntity(request);
        applyDefaults(entity);
        IssueRequest saved = repository.save(entity);
        for (int index = 0; index < request.getLines().size(); index++) {
            lineRepository.save(mapper.toLineEntity(saved.getId(), request.getLines().get(index), index));
        }
        IssueRequestResponse response = response(saved, lines(saved.getId()));
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE", saved.getCode(), null, response);
        return response;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public IssueRequestResponse update(UUID id, com.enterprise.mm.payload.IssueRequestUpdateRequest request) {
        IssueRequest entity = findEntity(id);
        ensureSubmitter(entity);
        if (!List.of("DRAFT", "REJECTED").contains(entity.getStatus())) {
            throw new ConflictException("Only draft or rejected requests can be corrected");
        }
        if (request == null || request.getExpectedVersion() == null) throw new BadRequestException("expectedVersion is required");
        requireExpectedVersion(entity, request.getExpectedVersion());
        if (!Objects.equals(entity.getRequesterUserId(), request.getRequesterUserId())) {
            throw new BadRequestException("Requester cannot be changed");
        }
        var oldSources = lines(id).stream().map(IssueRequestLine::getSourceDemandPlanItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        var newSources = request.getLines() == null ? java.util.Set.<UUID>of() : request.getLines().stream()
            .map(IssueRequestCreateRequest.Line::getSourceDemandPlanItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!newSources.containsAll(oldSources)) throw new BusinessException("Keep the original demand source when correcting this request", "demandSource.required");
        validateCreateRequest(request, id);
        String oldSnapshot = snapshotSerializer.toJson(response(entity, lines(id)));
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setWarehouseId(request.getWarehouseId());
        entity.setReceiverUserId(request.getReceiverUserId());
        entity.setCostObjectType(request.getCostObjectType());
        entity.setCostObjectId(request.getCostObjectId());
        entity.setRequiredDate(request.getRequiredDate());
        entity.setPurpose(request.getPurpose());
        entity.setStatus(IssueRequestStatus.DRAFT.name());
        entity.setApprovalComment(null);
        for (IssueRequestLine line : lines(id)) { line.softDelete(); lineRepository.save(line); }
        for (int index = 0; index < request.getLines().size(); index++) {
            lineRepository.save(mapper.toLineEntity(id, request.getLines().get(index), index));
        }
        IssueRequestResponse result = response(repository.save(entity), lines(id));
        auditService.log(ENTITY_NAME, id, "UPDATE", entity.getCode(), oldSnapshot, result);
        return result;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public IssueRequestResponse submit(UUID id) {
        IssueRequest entity = findEntity(id);
        requireStatus(entity, IssueRequestStatus.DRAFT);
        ensureSubmitter(entity);
        List<IssueRequestLine> lines = lines(id);
        if (lines.isEmpty()) {
            throw new BadRequestException("Issue request must contain at least one line");
        }
        validateDemandSources(entity);
        String oldSnapshot = snapshotSerializer.toJson(response(entity, lines));
        entity.setStatus(IssueRequestStatus.SUBMITTED.name());
        IssueRequest saved = repository.save(entity);
        IssueRequestResponse response = response(saved, lines);
        auditService.log(ENTITY_NAME, id, "SUBMIT", saved.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD')")
    public IssueRequestResponse approve(UUID id, IssueRequestApproveRequest request) {
        IssueRequest entity = findEntity(id);
        requireStatus(entity, IssueRequestStatus.SUBMITTED);
        requireExpectedVersion(entity, request == null ? null : request.getExpectedVersion());
        if (Objects.equals(currentUserId(), entity.getRequesterUserId())) {
            throw new AccessDeniedException("Requester cannot approve their own issue request");
        }
        List<IssueRequestLine> lines = lines(id);
        validateDemandSources(entity);
        String oldSnapshot = snapshotSerializer.toJson(response(entity, lines));
        Map<UUID, BigDecimal> requestedApprovals = approvedQuantities(lines, request);
        BigDecimal totalApproved = BigDecimal.ZERO;
        for (IssueRequestLine line : lines) {
            BigDecimal approved = requestedApprovals.getOrDefault(line.getId(), nvl(line.getRequestedQuantity()));
            if (approved.compareTo(nvl(line.getRequestedQuantity())) > 0) {
                throw new BadRequestException("approvedQuantity cannot exceed requestedQuantity for line " + line.getId());
            }
            line.setApprovedQuantity(approved);
            lineRepository.save(line);
            totalApproved = totalApproved.add(approved);
        }
        if (totalApproved.signum() <= 0) {
            throw new BadRequestException("Approved quantity must be positive for at least one line");
        }
        entity.setApprovalComment(request == null ? null : request.getComment());
        entity.setStatus(IssueRequestStatus.APPROVED.name());
        IssueRequest saved = repository.save(entity);
        IssueRequestResponse response = response(saved, lines);
        auditService.log(ENTITY_NAME, id, "APPROVE", saved.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD')")
    public IssueRequestResponse reject(UUID id, ServiceActionRequest request) {
        IssueRequest entity = findEntity(id);
        if (Objects.equals(currentUserId(), entity.getRequesterUserId())) throw new AccessDeniedException("Requester cannot review their own issue request");
        requireStatus(entity, IssueRequestStatus.SUBMITTED);
        String oldSnapshot = snapshotSerializer.toJson(response(entity, lines(id)));
        entity.setApprovalComment(request == null ? null : request.comment());
        entity.setStatus(IssueRequestStatus.REJECTED.name());
        IssueRequest saved = repository.save(entity);
        IssueRequestResponse response = response(saved, lines(id));
        auditService.log(ENTITY_NAME, id, "REJECT", saved.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public IssueRequestResponse reserve(UUID id, IssueRequestReserveRequest request) {
        IssueRequest entity = findEntity(id);
        requireStatus(entity, IssueRequestStatus.APPROVED);
        UUID warehouseId = request != null && request.getWarehouseId() != null ? request.getWarehouseId() : entity.getWarehouseId();
        if (!Objects.equals(warehouseId, entity.getWarehouseId())) {
            throw new BadRequestException("Reserve warehouseId must match issue request warehouse");
        }
        List<IssueRequestLine> lines = lines(id);
        validateDemandSources(entity);
        String oldSnapshot = snapshotSerializer.toJson(response(entity, lines));
        Map<UUID, IssueRequestReserveRequest.Line> overrides = reserveOverrides(request);
        for (IssueRequestLine line : lines) {
            BigDecimal quantity = nvl(line.getApprovedQuantity()).subtract(nvl(line.getIssuedQuantity()));
            if (quantity.signum() <= 0) {
                continue;
            }
            IssueRequestReserveRequest.Line override = overrides.get(line.getId());
            BigDecimal reservedQuantity = override != null && override.getReservedQuantity() != null ? override.getReservedQuantity() : quantity;
            if (reservedQuantity.signum() <= 0 || reservedQuantity.compareTo(quantity) > 0) {
                throw new BadRequestException("reservedQuantity exceeds approved balance for line " + line.getId());
            }
            if (line.getReservationId() != null) {
                continue;
            }
            MaterialReservationCreateRequest reservationRequest = new MaterialReservationCreateRequest();
            reservationRequest.setName(BusinessNameGenerator.workflowDocumentName("MaterialReservation", entity.getName()));
            reservationRequest.setMaterialId(line.getMaterialId());
            reservationRequest.setWarehouseId(entity.getWarehouseId());
            reservationRequest.setStorageLocationId(override == null ? null : override.getStorageLocationId());
            reservationRequest.setStockBatchId(override == null ? null : override.getStockBatchId());
            reservationRequest.setStorageBinId(override == null ? null : override.getStorageBinId());
            if (requireMaterial(line.getMaterialId()).getSerialTrackingPolicy() != SerialTrackingPolicy.REQUIRED)
                ensureAvailableStock(line.getMaterialId(), entity.getWarehouseId(), reservationRequest.getStorageLocationId(), reservationRequest.getStorageBinId(), reservationRequest.getStockBatchId(), reservedQuantity);
            reservationRequest.setQuantity(reservedQuantity);
            reservationRequest.setRequiredDate(entity.getRequiredDate());
            reservationRequest.setReferenceType("IssueRequest");
            reservationRequest.setReferenceId(entity.getId());
            reservationRequest.setReservationType(reservationType(entity.getCostObjectType()));
            reservationRequest.setReservedBy(currentUsername());
            reservationRequest.setMaterialUnitIds(override == null ? null : override.getMaterialUnitIds());
            MaterialReservationResponse reservation = materialReservationService.create(reservationRequest);
            line.setReservationId(reservation.getId());
            line.setStorageLocationId(reservation.getStorageLocationId());
            line.setStockBatchId(reservation.getStockBatchId());
            lineRepository.save(line);
        }
        entity.setStatus(IssueRequestStatus.RESERVED.name());
        IssueRequest saved = repository.save(entity);
        IssueRequestResponse response = response(saved, lines(id));
        auditService.log(ENTITY_NAME, id, "RESERVE", saved.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public IssueRequestResponse issue(UUID id, IssueRequestIssueRequest request) {
        return issue(id, request, null);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public IssueRequestResponse issue(UUID id, IssueRequestIssueRequest request, String idempotencyKey) {
        repository.lockExecution(id, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Issue request is unavailable"));
        findEntity(id); // Recheck warehouse/department access even on an idempotent replay.
        return idempotencyService.executeRequired(
            "ISSUE_REQUEST_ISSUE",
            id,
            idempotencyKey,
            request,
            IssueRequestResponse.class,
            () -> issueInternal(id, request)
        );
    }

    private IssueRequestResponse issueInternal(UUID id, IssueRequestIssueRequest request) {
        IssueRequest entity = findEntity(id);
        if (!List.of(
            IssueRequestStatus.APPROVED.name(),
            IssueRequestStatus.RESERVED.name(),
            IssueRequestStatus.PARTIALLY_ISSUED.name()
        ).contains(entity.getStatus())) {
            throw new ConflictException("Issue request must be APPROVED, RESERVED or PARTIALLY_ISSUED, current status is " + entity.getStatus());
        }
        if (request == null || request.getWarehouseId() == null) {
            throw new BadRequestException("warehouseId is required");
        }
        requireExpectedVersion(entity, request.getExpectedVersion());
        if (!Objects.equals(request.getWarehouseId(), entity.getWarehouseId())) {
            throw new BadRequestException("Issue warehouseId must match issue request warehouse");
        }
        List<IssueRequestLine> lines = lines(id);
        validateDemandSources(entity);
        String oldSnapshot = snapshotSerializer.toJson(response(entity, lines));
        Map<UUID, IssueRequestIssueRequest.Line> overrides = issueOverrides(request, lines);
        List<StockMovementDocumentItemRequest> documentItems = new ArrayList<>();
        List<IssuePostingBinding> postingBindings = new ArrayList<>();
        for (IssueRequestLine line : lines) {
            IssueRequestIssueRequest.Line override = overrides.get(line.getId());
            if (override == null) continue;
            BigDecimal approvedBalance = nvl(line.getApprovedQuantity()).subtract(nvl(line.getIssuedQuantity()));
            BigDecimal issueQuantity = override.getIssuedQuantity();
            if (issueQuantity.signum() <= 0 || issueQuantity.compareTo(approvedBalance) > 0) {
                throw new BadRequestException("issuedQuantity exceeds approved balance for line " + line.getId());
            }
            UUID storageLocationId = override != null && override.getStorageLocationId() != null ? override.getStorageLocationId() : firstNonNull(line.getStorageLocationId(), request.getStorageLocationId());
            UUID stockBatchId = override != null && override.getStockBatchId() != null ? override.getStockBatchId() : line.getStockBatchId();
            UUID storageBinId = override == null ? null : override.getStorageBinId();
            Material material = requireMaterial(line.getMaterialId());
            boolean serialized = material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED;
            List<MaterialUnit> selectedUnits = List.of();

            if (line.getReservationId() != null) {
                var reservation = materialReservationService.get(line.getReservationId());
                if (!Objects.equals(reservation.getStockBatchId(), stockBatchId)
                    || !Objects.equals(reservation.getStorageLocationId(), storageLocationId)
                    || (storageBinId != null && !Objects.equals(reservation.getStorageBinId(), storageBinId)))
                    throw new BusinessException("Use the position reserved for this issue request", "stockPosition.reservation");
                storageBinId = reservation.getStorageBinId();
                BigDecimal reservedQuantity = nvl(reservation.getReservedQuantity());
                BigDecimal releasable = reservedQuantity.min(issueQuantity);
                if (releasable.signum() > 0) {
                    materialReservationService.consumeForStockPosting(
                        line.getReservationId(),
                        new ConsumeRequest(
                            releasable,
                            "Issued from issue request",
                            reservation.getVersion(),
                            "ISSUE_REQUEST:" + entity.getId()
                        ),
                        "issue-request-consume-" + entity.getId() + "-" + line.getId()
                            + "-" + nvl(line.getIssuedQuantity()).toPlainString()
                    );
                }
            }

            if (serialized) {
                selectedUnits = validateSerializedIssueSelection(
                    line,
                    override == null ? null : override.getMaterialUnitIds(),
                    entity.getWarehouseId(),
                    storageLocationId,
                    issueQuantity
                );
                validateReservedUnitsBelongToLine(line, selectedUnits);
                if (stockBatchId != null && selectedUnits.stream().anyMatch(unit -> !Objects.equals(stockBatchId, unit.getStockBatchId()))) {
                    throw new BadRequestException("MATERIAL_UNIT_WRONG_BATCH");
                }
                Map<IssueStockKey, List<MaterialUnit>> groupedUnits = selectedUnits.stream()
                    .collect(Collectors.groupingBy(
                        unit -> new IssueStockKey(
                            unit.getStorageLocationId() == null ? storageLocationId : unit.getStorageLocationId(),
                            unit.getStockBatchId()
                        ),
                        LinkedHashMap::new,
                        Collectors.toList()
                    ));
                for (Map.Entry<IssueStockKey, List<MaterialUnit>> entry : groupedUnits.entrySet()) {
                    BigDecimal groupQuantity = BigDecimal.valueOf(entry.getValue().size());
                    ensureAvailableStock(line.getMaterialId(), entity.getWarehouseId(), entry.getKey().storageLocationId(), entry.getKey().stockBatchId(), groupQuantity);
                    StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
                    item.setMaterialId(line.getMaterialId());
                    item.setStorageLocationId(entry.getKey().storageLocationId());
                    item.setStockBatchId(entry.getKey().stockBatchId());
                    item.setQuantity(groupQuantity);
                    item.setUnitCost(line.getUnitCost());
                    documentItems.add(item);
                    postingBindings.add(new IssuePostingBinding(line, entry.getValue(), true));
                }
            } else {
                ensureAvailableStock(line.getMaterialId(), entity.getWarehouseId(), storageLocationId, storageBinId, stockBatchId, issueQuantity);

                StockMovementDocumentItemRequest item = new StockMovementDocumentItemRequest();
                item.setMaterialId(line.getMaterialId());
                item.setStorageLocationId(storageLocationId);
                item.setStorageBinId(storageBinId);
                item.setStockBatchId(stockBatchId);
                item.setQuantity(issueQuantity);
                item.setUnitCost(line.getUnitCost());
                documentItems.add(item);
                postingBindings.add(new IssuePostingBinding(line, List.of(), false));
            }

            line.setIssuedQuantity(nvl(line.getIssuedQuantity()).add(issueQuantity));
            line.setStorageLocationId(storageLocationId);
            if (!serialized || selectedUnits.stream().map(MaterialUnit::getStockBatchId).distinct().count() <= 1) {
                line.setStockBatchId(serialized && !selectedUnits.isEmpty() ? selectedUnits.get(0).getStockBatchId() : stockBatchId);
            }
            lineRepository.save(line);
        }
        if (documentItems.isEmpty()) {
            throw new BadRequestException("Issue request has no approved quantities to issue");
        }

        StockMovementDocumentCreateRequest documentRequest = new StockMovementDocumentCreateRequest();
        documentRequest.setCode(entity.getCode() + "-ISS-" + (entity.getVersion() + 1));
        documentRequest.setName(entity.getName());
        documentRequest.setDescription(entity.getPurpose() == null ? entity.getDescription() : entity.getPurpose());
        documentRequest.setWarehouseId(entity.getWarehouseId());
        documentRequest.setMovementType(MovementType.ISSUE);
        documentRequest.setMovementDate(request.getIssuedDate() == null ? LocalDate.now() : request.getIssuedDate());
        documentRequest.setReferenceType("ISSUE_REQUEST");
        documentRequest.setReferenceId(entity.getId());
        documentRequest.setItems(documentItems);

        var createdDocument = stockMovementDocumentService.create(documentRequest);
        stockMovementDocumentService.changeStatus(createdDocument.getId(), new StockMovementStatusChangeRequest(StockMovementStatus.PENDING));
        var approvedDocument = stockMovementDocumentService.changeStatus(
            createdDocument.getId(),
            new StockMovementStatusChangeRequest(StockMovementStatus.APPROVED)
        );
        var postedDocument = stockMovementDocumentService.postManagedIssue(
            createdDocument.getId(),
            new StockMovementPostRequest(approvedDocument.getVersion()),
            "issue-request-post-" + entity.getId() + "-" + createdDocument.getId()
        );

        for (int index = 0; index < postingBindings.size(); index++) {
            IssuePostingBinding binding = postingBindings.get(index);
            IssueRequestLine line = binding.line();
            var postedItem = postedDocument.getItems().get(index);
            line.setUnitCost(postedItem.getUnitCost());
            if (!binding.serialized() || binding.units().stream().map(MaterialUnit::getStockBatchId).distinct().count() <= 1) {
                line.setStockBatchId(postedItem.getStockBatchId());
            }
            if (line.getStockMovementId() == null) {
                line.setStockMovementId(postedItem.getId());
            }
            line.setStorageLocationId(postedItem.getStorageLocationId());
            lineRepository.save(line);
            if (binding.serialized()) {
                materialUnitEligibilityService.markIssued(
                    binding.units(),
                    ENTITY_NAME,
                    entity.getId(),
                    line.getId(),
                    entity.getWarehouseId(),
                    postedItem.getStorageLocationId(),
                    null
                );
            }
        }

        entity.setIssuedDate(documentRequest.getMovementDate());
        entity.setStockMovementDocumentId(postedDocument.getId());
        boolean fullyIssued = lines(id).stream().allMatch(line ->
            nvl(line.getApprovedQuantity()).subtract(nvl(line.getIssuedQuantity())).signum() <= 0
        );
        entity.setStatus(fullyIssued ? IssueRequestStatus.ISSUED.name() : IssueRequestStatus.PARTIALLY_ISSUED.name());
        IssueRequest saved = repository.save(entity);
        IssueRequestResponse response = response(saved, lines(id));
        auditService.log(ENTITY_NAME, id, "ISSUE", postedDocument.getId().toString(), oldSnapshot, response);
        List<ErpExchangeService.Line> exchangeLines = postedDocument.getItems().stream()
            .map(item -> new ErpExchangeService.Line(item.getMaterialId(), item.getWarehouseId(), item.getQuantity(), item.getTotalCost())).toList();
        ErpExchangeHooks.accept(erpExchange, "ISSUE", saved.getId(), saved.getVersion(), saved.getIssuedDate(), "ISSUE", saved.getWarehouseId(), null, exchangeLines);
        return response;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','DEPARTMENT_HEAD')")
    public IssueRequestResponse close(UUID id, ServiceActionRequest request) {
        IssueRequest entity = findEntity(id);
        if (!List.of(IssueRequestStatus.ISSUED.name(), IssueRequestStatus.PARTIALLY_ISSUED.name()).contains(entity.getStatus())) {
            throw new ConflictException("Issue request must be ISSUED or PARTIALLY_ISSUED, current status is " + entity.getStatus());
        }
        if (IssueRequestStatus.PARTIALLY_ISSUED.name().equals(entity.getStatus())
            && (request == null || request.comment() == null || request.comment().isBlank())) {
            throw new BadRequestException("Closing a partially issued request requires a shortage resolution comment");
        }
        String oldSnapshot = snapshotSerializer.toJson(response(entity, lines(id)));
        for (IssueRequestLine line : lines(id)) releaseOutstandingReservation(line);
        entity.setStatus(IssueRequestStatus.CLOSED.name());
        IssueRequest saved = repository.save(entity);
        IssueRequestResponse response = response(saved, lines(id));
        auditService.log(ENTITY_NAME, id, "CLOSE", request == null ? saved.getCode() : request.comment(), oldSnapshot, response);
        return response;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD','DEPARTMENT_WORKER','WAREHOUSE_MANAGER')")
    public IssueRequestResponse cancel(UUID id, ServiceActionRequest request) {
        IssueRequest entity = findEntity(id);
        if (!hasRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "DEPARTMENT_HEAD")) ensureSubmitter(entity);
        if (List.of(
            IssueRequestStatus.PARTIALLY_ISSUED.name(),
            IssueRequestStatus.ISSUED.name(),
            IssueRequestStatus.CLOSED.name()
        ).contains(entity.getStatus())) {
            throw new ConflictException("Partially issued, issued or closed issue request cannot be cancelled");
        }
        if (IssueRequestStatus.CANCELLED.name().equals(entity.getStatus())) {
            return response(entity, lines(id));
        }
        String oldSnapshot = snapshotSerializer.toJson(response(entity, lines(id)));
        for (IssueRequestLine line : lines(id)) {
            releaseOutstandingReservation(line);
        }
        entity.setApprovalComment(request == null ? entity.getApprovalComment() : request.comment());
        entity.setStatus(IssueRequestStatus.CANCELLED.name());
        IssueRequest saved = repository.save(entity);
        IssueRequestResponse response = response(saved, lines(id));
        auditService.log(ENTITY_NAME, id, "CANCEL", request == null ? saved.getCode() : request.comment(), oldSnapshot, response);
        return response;
    }

    IssueRequest findEntity(UUID id) {
        IssueRequest entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Issue request not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())
            || !roleDataScopeService.canAccessWarehouse(entity.getWarehouseId())
            || (roleDataScopeService.isDepartmentWarehouseScoped()
                && !roleDataScopeService.canReadIssueDepartment(owningDepartment(entity)))) {
            throw new NotFoundException("Issue request not found: " + id);
        }
        return entity;
    }

    private UUID owningDepartment(IssueRequest entity) {
        if (entity.getCostObjectType() == IssueRequestCostObjectType.DEPARTMENT) return entity.getCostObjectId();
        User requester = entity.getRequesterUser();
        if (requester == null && entity.getRequesterUserId() != null) {
            requester = userRepository.findByIdAndIsDeletedFalse(entity.getRequesterUserId()).orElse(null);
        }
        return userDepartment(requester);
    }

    private UUID userDepartment(User user) {
        if (user == null) return null;
        if (user.getEmployee() != null && user.getEmployee().getDepartment() != null)
            return user.getEmployee().getDepartment().getId();
        return user.getDepartment() == null ? null : user.getDepartment().getId();
    }

    private boolean hasRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> java.util.Arrays.asList(roles).contains(a.getAuthority().replaceFirst("^ROLE_", "")));
    }

    public Map<String, Boolean> permissions() {
        return Map.of("create", hasRole("ADMIN", "MM_MANAGER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER")
            && (!roleDataScopeService.isDepartmentIssueScoped() || roleDataScopeService.issueDepartmentId() != null));
    }

    private IssueRequestResponse response(IssueRequest entity, List<IssueRequestLine> lines) {
        IssueRequestResponse result = mapper.toResponse(entity, lines);
        List<String> candidates = new ArrayList<>(result.getAllowedActions());
        if (List.of("DRAFT", "REJECTED").contains(entity.getStatus())) candidates.add("edit");
        boolean owner = Objects.equals(currentUserId(), entity.getRequesterUserId())
            || Objects.equals(currentUsername(), entity.getCreatedBy());
        boolean requester = hasRole("ADMIN", "MM_MANAGER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
        boolean reviewer = hasRole("ADMIN", "MM_MANAGER", "DEPARTMENT_HEAD")
            && !Objects.equals(currentUserId(), entity.getRequesterUserId());
        result.setAllowedActions(candidates.stream().filter(action -> switch (action) {
            case "edit", "submit" -> requester && owner;
            case "approve", "reject" -> reviewer;
            case "reserve", "issue" -> hasRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER");
            case "close" -> hasRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD");
            case "cancel" -> hasRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "DEPARTMENT_HEAD") || (requester && owner);
            default -> false;
        }).toList());
        return result;
    }

    private void applyDefaults(IssueRequest entity) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("IR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT));
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(IssueRequestStatus.DRAFT.name());
        }
    }

    private void validateCreateRequest(IssueRequestCreateRequest request, UUID excludedRequest) {
        requireWarehouse(request.getWarehouseId());
        User requester = requireUser(request.getRequesterUserId(), "requesterUserId");
        User receiver = requireUser(request.getReceiverUserId(), "receiverUserId");
        if (!hasRole("ADMIN", "MM_MANAGER") && !Objects.equals(currentUserId(), requester.getId())) {
            throw new AccessDeniedException("Only the current requester can originate an issue request");
        }
        if (roleDataScopeService.isDepartmentIssueScoped()) {
            if (request.getCostObjectType() != IssueRequestCostObjectType.DEPARTMENT) {
                var item = demandPlanItemRepository.findByIdAndIsDeletedFalse(request.getCostObjectId())
                    .filter(i -> Objects.equals(i.getTenantId(), TenantContext.currentTenant())
                        && Objects.equals(i.getCompanyId(), CompanyContext.currentCompany())
                        && i.getDemandPlan() != null && i.getDemandPlan().getDepartment() != null
                        && roleDataScopeService.canReadIssueDepartment(i.getDemandPlan().getDepartment().getId()))
                    .orElseThrow(() -> new AccessDeniedException("Cost object must belong to the permitted department"));
                boolean eligible = request.getCostObjectType() == IssueRequestCostObjectType.PROJECT
                    ? ("PROJECT".equals(item.getDemandType()) || "CAPEX".equals(item.getDemandType()))
                    : item.getRepairOrderCode() != null && !item.getRepairOrderCode().isBlank();
                if (!eligible) throw new BadRequestException("Cost object type does not match demand item");
            }
            UUID department = request.getCostObjectType() == IssueRequestCostObjectType.DEPARTMENT
                ? request.getCostObjectId() : userDepartment(requester);
            if (!roleDataScopeService.canReadIssueDepartment(department)
                || !roleDataScopeService.canReadIssueDepartment(userDepartment(receiver))) {
                throw new AccessDeniedException("Issue request must belong to the permitted department");
            }
        }
        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw new BadRequestException("Issue request must contain at least one line");
        }
        for (IssueRequestCreateRequest.Line line : request.getLines()) {
            if (line.getMaterialId() == null) {
                throw new BadRequestException("materialId is required");
            }
            if (nvl(line.getRequestedQuantity()).signum() <= 0) {
                throw new BadRequestException("requestedQuantity must be positive");
            }
            requireMaterial(line.getMaterialId());
        }
        UUID department = request.getCostObjectType() == IssueRequestCostObjectType.DEPARTMENT
            ? request.getCostObjectId() : userDepartment(requester);
        validateDemandSources(department, request.getLines(), excludedRequest);
    }

    private void validateDemandSources(UUID department, List<IssueRequestCreateRequest.Line> sourceLines, UUID excludedRequest) {
        // Lock in deterministic order; concurrent drafts cannot claim the same stock approval twice.
        var sources = sourceLines.stream().map(IssueRequestCreateRequest.Line::getSourceDemandPlanItemId)
            .filter(Objects::nonNull).distinct().sorted().toList();
        for (UUID sourceId : sources) {
            var source = demandPlanItemRepository.lockSource(sourceId, TenantContext.currentTenant(), CompanyContext.currentCompany())
                .orElseThrow(() -> new BusinessException("The demand source is unavailable for this company", "demandSource.unavailable"));
            var plan = source.getDemandPlan();
            UUID sourceDepartment = source.getDepartmentId() != null ? source.getDepartmentId()
                : plan == null || plan.getDepartment() == null ? null : plan.getDepartment().getId();
            if (!Objects.equals(sourceDepartment, department)) {
                throw new BusinessException("The demand source belongs to another department", "demandSource.department");
            }
            if (plan == null || !Objects.equals(plan.getTenantId(), TenantContext.currentTenant())
                || !Objects.equals(plan.getCompanyId(), CompanyContext.currentCompany())
                || !List.of("APPROVED", "ACTIVE").contains(plan.getStatus())
                || !List.of("APPROVED", "RESERVED", "CONVERTED_TO_PR").contains(source.getStatus())) {
                throw new BusinessException("The demand source must be independently approved first", "demandSource.approval");
            }
            BigDecimal requested = BigDecimal.ZERO;
            for (var line : sourceLines) if (sourceId.equals(line.getSourceDemandPlanItemId())) {
                if (!Objects.equals(source.getMaterialId(), line.getMaterialId()))
                    throw new BusinessException("The material does not match the demand source", "demandSource.material");
                requested = requested.add(line.getRequestedQuantity());
            }
            BigDecimal claimed = lineRepository.findWorkspaceDemandSourceLines(TenantContext.currentTenant(), CompanyContext.currentCompany(), List.of(sourceId))
                .stream().filter(line -> !Objects.equals(line.getIssueRequestId(), excludedRequest))
                .map(line -> {
                    String status = sourceRequestStatus(line);
                    if (List.of("CANCELLED", "REJECTED").contains(status)) return BigDecimal.ZERO;
                    return "CLOSED".equals(status) ? nvl(line.getIssuedQuantity()) : nvl(line.getRequestedQuantity());
                }).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (claimed.add(requested).compareTo(nvl(source.getStockFulfillmentQuantity())) > 0)
                throw new BusinessException("The demand stock portion is already claimed or needs stock-review approval", "demandSource.quantity");
        }
    }

    private String sourceRequestStatus(IssueRequestLine line) {
        var request = line.getIssueRequest() != null ? line.getIssueRequest()
            : repository.findByIdAndIsDeletedFalse(line.getIssueRequestId()).orElseThrow(() -> new NotFoundException("Issue request is unavailable"));
        return request.getStatus();
    }

    private void validateDemandSources(IssueRequest document) {
        var sourceLines = lines(document.getId()).stream().map(line -> {
            var value = new IssueRequestCreateRequest.Line(); value.setMaterialId(line.getMaterialId());
            value.setRequestedQuantity(line.getRequestedQuantity()); value.setSourceDemandPlanItemId(line.getSourceDemandPlanItemId());
            return value;
        }).toList();
        validateDemandSources(owningDepartment(document), sourceLines, document.getId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> demandSource(UUID id) {
        var source = demandPlanItemRepository.findByIdAndIsDeletedFalse(id)
            .filter(s -> Objects.equals(s.getTenantId(), TenantContext.currentTenant()) && Objects.equals(s.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new BusinessException("The demand source is unavailable", "demandSource.unavailable"));
        var plan = source.getDemandPlan();
        UUID department = source.getDepartmentId() != null ? source.getDepartmentId() : plan.getDepartment().getId();
        if (!roleDataScopeService.canReadIssueDepartment(department)) throw new AccessDeniedException("The demand source belongs to another department");
        if (!List.of("APPROVED", "ACTIVE").contains(plan.getStatus())) throw new BusinessException("The demand source requires approval", "demandSource.approval");
        BigDecimal claimed = lineRepository.findWorkspaceDemandSourceLines(TenantContext.currentTenant(), CompanyContext.currentCompany(), List.of(id)).stream()
            .map(line -> List.of("CANCELLED", "REJECTED").contains(sourceRequestStatus(line)) ? BigDecimal.ZERO
                : "CLOSED".equals(sourceRequestStatus(line)) ? nvl(line.getIssuedQuantity()) : nvl(line.getRequestedQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("sourceDemandPlanItemId", id, "materialId", source.getMaterialId(), "departmentId", department,
            "requesterUserId", currentUserId(), "requestedQuantity", source.getRequestedQuantity(),
            "stockFulfillmentQuantity", source.getStockFulfillmentQuantity(), "procurementQuantity", source.getApprovedQuantity(),
            "remainingQuantity", source.getStockFulfillmentQuantity().subtract(claimed).max(BigDecimal.ZERO));
    }

    private Warehouse requireWarehouse(UUID warehouseId) {
        if (warehouseId == null) {
            throw new BadRequestException("warehouseId is required");
        }
        return warehouseRepository.findByIdAndIsDeletedFalse(warehouseId)
            .filter(warehouse -> TenantContext.currentTenant().equals(warehouse.getTenantId())
                && com.enterprise.mm.security.AccessScope.canAccessCompany(warehouse.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Warehouse not found: " + warehouseId));
    }

    private User requireUser(UUID userId, String field) {
        if (userId == null) {
            throw new BadRequestException(field + " is required");
        }
        return userRepository.findByIdAndIsDeletedFalse(userId)
            .filter(user -> TenantContext.currentTenant().equals(user.getTenantId())
                && com.enterprise.mm.security.AccessScope.canAccessCompany(user.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    private Material requireMaterial(UUID materialId) {
        Material material = materialRepository.findByIdAndIsDeletedFalse(materialId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId())
                && com.enterprise.mm.security.AccessScope.canAccessCompany(candidate.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Material not found: " + materialId));
        if (material.getMaterialType() != MaterialType.TMB) {
            throw new BusinessException(
                "Issue request stock lines require TMB material: " + materialId,
                material.getMaterialType() == MaterialType.FIXED_ASSET ? "FIXED_ASSET_STOCK_FORBIDDEN" : "TMB_STOCK_ONLY",
                "materialId",
                material.getMaterialType()
            );
        }
        return material;
    }

    private void ensureSubmitter(IssueRequest entity) {
        UUID currentUserId = currentUserId();
        if (currentUserId == null) {
            throw new AccessDeniedException("Authentication is required");
        }
        boolean allowed = Objects.equals(currentUserId, entity.getRequesterUserId())
            || Objects.equals(currentUsername(), entity.getCreatedBy());
        if (!allowed) {
            throw new AccessDeniedException("Only creator or requester can submit issue request");
        }
    }

    private void requireStatus(IssueRequest entity, IssueRequestStatus expected) {
        if (!expected.name().equals(entity.getStatus())) {
            throw new ConflictException("Issue request must be " + expected.name() + ", current status is " + entity.getStatus());
        }
    }

    private void requireExpectedVersion(IssueRequest entity, Long expectedVersion) {
        if (expectedVersion != null && !Objects.equals(expectedVersion, entity.getVersion())) {
            throw new ConflictException(
                "Issue request version conflict: expected=" + expectedVersion + ", actual=" + entity.getVersion()
            );
        }
    }

    private List<IssueRequestLine> lines(UUID issueRequestId) {
        return lineRepository.findByTenantIdAndIssueRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(),
            issueRequestId
        );
    }

    private Map<UUID, BigDecimal> approvedQuantities(List<IssueRequestLine> lines, IssueRequestApproveRequest request) {
        Map<UUID, BigDecimal> values = new HashMap<>();
        if (request == null || request.getLines() == null) {
            return values;
        }
        Map<UUID, IssueRequestLine> existing = lines.stream().collect(java.util.stream.Collectors.toMap(IssueRequestLine::getId, line -> line));
        for (IssueRequestApproveRequest.Line line : request.getLines()) {
            if (line.getIssueRequestLineId() == null || !existing.containsKey(line.getIssueRequestLineId())) {
                throw new BadRequestException("issueRequestLineId does not belong to the issue request");
            }
            values.put(line.getIssueRequestLineId(), nvl(line.getApprovedQuantity()));
        }
        return values;
    }

    private Map<UUID, IssueRequestReserveRequest.Line> reserveOverrides(IssueRequestReserveRequest request) {
        Map<UUID, IssueRequestReserveRequest.Line> values = new HashMap<>();
        if (request == null || request.getLines() == null) {
            return values;
        }
        for (IssueRequestReserveRequest.Line line : request.getLines()) {
            if (line.getIssueRequestLineId() != null) {
                values.put(line.getIssueRequestLineId(), line);
            }
        }
        return values;
    }

    private Map<UUID, IssueRequestIssueRequest.Line> issueOverrides(IssueRequestIssueRequest request, List<IssueRequestLine> lines) {
        Map<UUID, IssueRequestIssueRequest.Line> values = new HashMap<>();
        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw new BadRequestException("Select at least one issue request line");
        }
        Map<UUID, IssueRequestLine> existing = lines.stream().collect(Collectors.toMap(IssueRequestLine::getId, line -> line));
        java.util.Set<UUID> selectedUnits = new java.util.HashSet<>();
        for (IssueRequestIssueRequest.Line line : request.getLines()) {
            if (line == null || line.getIssueRequestLineId() == null || !existing.containsKey(line.getIssueRequestLineId())) {
                throw new BadRequestException("Issue line does not belong to this request");
            }
            if (values.putIfAbsent(line.getIssueRequestLineId(), line) != null) {
                throw new BadRequestException("Duplicate issue request line");
            }
            IssueRequestLine source = existing.get(line.getIssueRequestLineId());
            BigDecimal remaining = nvl(source.getApprovedQuantity()).subtract(nvl(source.getIssuedQuantity()));
            if (line.getIssuedQuantity() == null || line.getIssuedQuantity().signum() <= 0
                || line.getIssuedQuantity().compareTo(remaining) > 0) {
                throw new BadRequestException("issuedQuantity must be positive and cannot exceed approved balance for line " + source.getId());
            }
            if (line.getMaterialUnitIds() != null && line.getMaterialUnitIds().stream().anyMatch(unit -> unit == null || !selectedUnits.add(unit))) {
                throw new BadRequestException("A serialized unit can only be selected once per issue");
            }
        }
        return values;
    }

    private List<MaterialUnit> validateSerializedIssueSelection(
        IssueRequestLine line,
        List<UUID> materialUnitIds,
        UUID warehouseId,
        UUID storageLocationId,
        BigDecimal issueQuantity
    ) {
        MaterialUnitStatus expectedStatus = line.getReservationId() == null
            ? MaterialUnitStatus.IN_STOCK
            : MaterialUnitStatus.RESERVED;
        return materialUnitEligibilityService.lockAndValidate(
            line.getMaterialId(),
            warehouseId,
            storageLocationId,
            null,
            issueQuantity,
            materialUnitIds,
            expectedStatus
        );
    }

    private void validateReservedUnitsBelongToLine(IssueRequestLine line, List<MaterialUnit> selectedUnits) {
        if (line.getReservationId() == null || selectedUnits.isEmpty()) {
            return;
        }
        List<UUID> reservationUnitIds = materialUnitEligibilityService.operationUnitIds(
            "MaterialReservation",
            line.getReservationId(),
            MaterialUnitOperationType.RESERVATION
        );
        if (!new LinkedHashSet<>(reservationUnitIds).containsAll(
            selectedUnits.stream().map(MaterialUnit::getId).toList()
        )) {
            throw new BadRequestException("MATERIAL_UNIT_NOT_AVAILABLE");
        }
    }

    private void ensureAvailableStock(UUID materialId, UUID warehouseId, UUID storageLocationId, UUID stockBatchId, BigDecimal requested) {
        ensureAvailableStock(materialId, warehouseId, storageLocationId, null, stockBatchId, requested);
    }
    private void ensureAvailableStock(UUID materialId, UUID warehouseId, UUID storageLocationId, UUID storageBinId, UUID stockBatchId, BigDecimal requested) {
        StockBalance balance = stockBalanceRepository.findCompanyBalanceForUpdate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            materialId,
            warehouseId,
            storageLocationId == null,
            storageLocationId,
            storageBinId == null,
            storageBinId,
            stockBatchId == null,
            stockBatchId
        ).orElseThrow(() -> insufficientStock(materialId, warehouseId, requested, BigDecimal.ZERO));
        if (requireMaterial(materialId).getSerialTrackingPolicy() != SerialTrackingPolicy.REQUIRED) {
            String reason = StockPositionPolicy.blockedReason(balance);
            if (reason != null) throw new BusinessException("This stock position is unavailable. Ask the warehouse manager to review it", "stockPosition." + reason);
        }
        BigDecimal available = nvl(balance.getAvailableQuantity());
        if (available.compareTo(requested) < 0) {
            throw insufficientStock(materialId, warehouseId, requested, available);
        }
    }

    private InsufficientStockException insufficientStock(
        UUID materialId,
        UUID warehouseId,
        BigDecimal requested,
        BigDecimal available
    ) {
        Material material = requireMaterial(materialId);
        Warehouse warehouse = requireWarehouse(warehouseId);
        String unit = material.getUnitOfMeasure() == null
            ? null
            : firstNonBlank(
                material.getUnitOfMeasure().getMeasurementNameShort(),
                material.getUnitOfMeasure().getMeasurementNameShortRu(),
                material.getUnitOfMeasure().getName()
            );
        return new InsufficientStockException(
            materialId,
            material.getName(),
            warehouseId,
            warehouse.getName(),
            requested,
            available,
            unit
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void releaseOutstandingReservation(IssueRequestLine line) {
        if (line.getReservationId() == null) {
            return;
        }
        var reservation = materialReservationService.get(line.getReservationId());
        BigDecimal reservedBalance = nvl(reservation.getReservedQuantity());
        if (reservedBalance.signum() > 0) {
            materialReservationService.release(
                line.getReservationId(),
                new com.enterprise.mm.payload.DemandManagementDtos.ReleaseReservationRequest(
                    reservedBalance,
                    "Unused issue request reservation released",
                    reservation.getVersion()
                ),
                "issue-request-cancel-release-" + line.getIssueRequestId() + "-" + line.getId()
            );
        }
    }

    private String materialName(IssueRequestLine line) {
        return line.getMaterial() == null ? line.getMaterialId().toString() : line.getMaterial().getName();
    }

    private String reservationType(IssueRequestCostObjectType type) {
        if (type == IssueRequestCostObjectType.PROJECT) {
            return MaterialReservationType.PROJECT.name();
        }
        if (type == IssueRequestCostObjectType.REPAIR_ORDER) {
            return MaterialReservationType.REPAIR.name();
        }
        return MaterialReservationType.MANUAL.name();
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        return userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(TenantContext.currentTenant(), authentication.getName())
            .map(User::getId)
            .orElse(null);
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }

    private Specification<IssueRequest> spec(IssueRequestSearchRequest request) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            if (roleDataScopeService.isDepartmentWarehouseScoped()) {
                predicates.add(departmentScopePredicate(root, cb));
            }
            var scope = roleDataScopeService.currentScope();
            if (scope.warehouseScoped()) {
                predicates.add(scope.warehouseIds().isEmpty() ? cb.disjunction() : root.get("warehouseId").in(scope.warehouseIds()));
            }
            if (!request.includeDeleted()) {
                predicates.add(cb.isFalse(root.get("isDeleted")));
            }
            if (request.search() != null && !request.search().isBlank()) {
                String pattern = "%" + request.search().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("code")), pattern),
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern),
                    cb.like(cb.lower(root.get("purpose")), pattern)
                ));
            }
            if (request.status() != null && !request.status().isEmpty()) {
                predicates.add(root.get("status").in(request.status()));
            }
            eq(predicates, cb, root.get("warehouseId"), request.warehouseId());
            eq(predicates, cb, root.get("requesterUserId"), request.requesterUserId());
            eq(predicates, cb, root.get("costObjectId"), request.costObjectId());
            if (request.costObjectType() != null && !request.costObjectType().isBlank()) {
                predicates.add(cb.equal(root.get("costObjectType"), IssueRequestCostObjectType.valueOf(request.costObjectType().trim().toUpperCase(Locale.ROOT))));
            }
            dateRange(predicates, cb, root.get("requiredDate"), request.dateFrom(), request.dateTo());
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    Predicate departmentScopePredicate(
        jakarta.persistence.criteria.From<?, IssueRequest> issue,
        jakarta.persistence.criteria.CriteriaBuilder cb
    ) {
        UUID departmentId = roleDataScopeService.issueDepartmentId();
        if (departmentId == null) return cb.disjunction();
        var requester = issue.join("requesterUser", jakarta.persistence.criteria.JoinType.LEFT);
        var employee = requester.join("employee", jakarta.persistence.criteria.JoinType.LEFT);
        var employeeDepartment = employee.join("department", jakarta.persistence.criteria.JoinType.LEFT);
        var userDepartment = requester.join("department", jakarta.persistence.criteria.JoinType.LEFT);
        return cb.or(
            cb.and(cb.equal(issue.get("costObjectType"), IssueRequestCostObjectType.DEPARTMENT), cb.equal(issue.get("costObjectId"), departmentId)),
            cb.and(cb.notEqual(issue.get("costObjectType"), IssueRequestCostObjectType.DEPARTMENT),
                cb.equal(cb.coalesce(employeeDepartment.get("id"), userDepartment.get("id")), departmentId)));
    }

    private <T> void eq(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb, jakarta.persistence.criteria.Path<T> path, T value) {
        if (value != null) {
            predicates.add(cb.equal(path, value));
        }
    }

    private void dateRange(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder cb, jakarta.persistence.criteria.Path<LocalDate> path, LocalDate from, LocalDate to) {
        if (from != null) {
            predicates.add(cb.greaterThanOrEqualTo(path, from));
        }
        if (to != null) {
            predicates.add(cb.lessThanOrEqualTo(path, to));
        }
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record IssueStockKey(UUID storageLocationId, UUID stockBatchId) {}

    private record IssuePostingBinding(IssueRequestLine line, List<MaterialUnit> units, boolean serialized) {}
}

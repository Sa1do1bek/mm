package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.DemandPlanItem;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialReservation;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.enums.DemandPlanItemStatus;
import com.enterprise.mm.enums.DemandType;
import com.enterprise.mm.enums.MaterialReservationStatus;
import com.enterprise.mm.enums.MaterialReservationType;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialReservationMapper;
import com.enterprise.mm.payload.DemandManagementDtos.DemandReservationRequest;
import com.enterprise.mm.payload.DemandManagementDtos.ReleaseReservationRequest;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.CancelRequest;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.ConsumeRequest;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.ExpireRequest;
import com.enterprise.mm.payload.MaterialReservationCreateRequest;
import com.enterprise.mm.payload.MaterialReservationEventResponse;
import com.enterprise.mm.payload.MaterialReservationResponse;
import com.enterprise.mm.payload.MaterialReservationSearchRequest;
import com.enterprise.mm.payload.MaterialReservationUpdateRequest;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialReservationRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MaterialReservationService {
    private final InventoryFreezeGuardService inventoryFreezeGuard;
    private static final String ENTITY_NAME = "MaterialReservation";
    private static final String AVAILABLE_DISPOSITION = "AVAILABLE";
    private static final List<String> OPEN_STATUSES = List.of(
        MaterialReservationStatus.ACTIVE.name(),
        MaterialReservationStatus.PARTIALLY_RELEASED.name()
    );
    private static final List<String> TERMINAL_STATUSES = List.of(
        MaterialReservationStatus.RELEASED.name(),
        MaterialReservationStatus.CONSUMED.name(),
        MaterialReservationStatus.EXPIRED.name(),
        MaterialReservationStatus.CANCELLED.name()
    );

    private final MaterialReservationRepository repository;
    private final MaterialReservationMapper mapper;
    private final StockBalanceRepository stockBalanceRepository;
    private final DemandPlanItemRepository demandPlanItemRepository;
    private final DemandPlanItemService demandPlanItemService;
    private final MaterialCatalogGuard materialCatalogGuard;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;
    private final RoleDataScopeService roleDataScopeService;
    private final IdempotencyService idempotencyService;
    private final MaterialRepository materialRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final StorageBinRepository storageBinRepository;
    private final StockBatchRepository stockBatchRepository;
    private final ReservationStockMutationService stockMutationService;
    private final MaterialReservationEventService eventService;
    private final MaterialUnitEligibilityService materialUnitEligibilityService;
    private final Clock clock;

    /** Internal command entry point for an already idempotent parent transaction. */
    @Transactional
    public MaterialReservationResponse create(MaterialReservationCreateRequest request) {
        return createInternal(request, "internal-create-" + UUID.randomUUID());
    }

    @Transactional
    public MaterialReservationResponse create(MaterialReservationCreateRequest request, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        UUID commandId = UUID.nameUUIDFromBytes((TenantContext.currentTenant() + ":"
            + CompanyContext.currentCompany() + ":MATERIAL_RESERVATION_CREATE:" + key)
            .getBytes(StandardCharsets.UTF_8));
        return idempotencyService.execute(
            "MATERIAL_RESERVATION_CREATE", commandId, key, request, MaterialReservationResponse.class,
            () -> createInternal(request, key)
        );
    }

    private MaterialReservationResponse createInternal(MaterialReservationCreateRequest request, String eventKey) {
        if (request == null) {
            throw new BadRequestException("Reservation request is required");
        }
        MaterialReservation reservation = mapper.toEntity(request);
        initialize(reservation);
        Material material = validateAndSnapshotStockKey(reservation);
        StockUomPolicy.quantity(material, reservation.getQuantity(), false);
        boolean serialized = material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED;
        if (serialized && (request.getMaterialUnitIds() == null || request.getMaterialUnitIds().isEmpty())) {
            throw new BadRequestException("SERIAL_UNIT_SELECTION_REQUIRED");
        }
        StockBalance balance = lockBalance(reservation);
        ReservationStockMutationService.Mutation mutation = stockMutationService.reserve(balance, reservation.getQuantity());
        stockBalanceRepository.saveAndFlush(balance);
        MaterialReservation saved = repository.saveAndFlush(reservation);
        List<UUID> reservedMaterialUnitIds = List.of();
        if (serialized) {
            reservedMaterialUnitIds = materialUnitEligibilityService.reserve(
                saved.getMaterialId(),
                saved.getWarehouseId(),
                saved.getStorageLocationId(),
                saved.getStorageBinId(),
                saved.getQuantity(),
                request.getMaterialUnitIds(),
                ENTITY_NAME,
                saved.getId(),
                null
            ).stream().map(com.enterprise.mm.entity.MaterialUnit::getId).toList();
        }
        syncDemandItemReservedQuantity(saved.getDemandPlanItemId(), saved.getQuantity());
        eventService.append(saved, balance, "CREATED", eventKey, null, saved.getQuantity(), mutation,
            null, "Reservation created", saved.getReferenceType() == null ? null : reference(saved));
        integrationEventService.emit("MM_MATERIAL_RESERVED", ENTITY_NAME, saved.getId(), Map.of(
            "reservationId", saved.getId(),
            "materialId", saved.getMaterialId(),
            "warehouseId", saved.getWarehouseId(),
            "quantity", saved.getQuantity(),
            "storageKey", stockKey(saved)
        ));
        MaterialReservationResponse response = mapper.toResponse(saved);
        response.setReservedMaterialUnitIds(reservedMaterialUnitIds);
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE_RESERVE", saved.getCode(), null, response);
        return response;
    }

    @Transactional
    public MaterialReservationResponse release(UUID id, ReleaseReservationRequest request, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        requireExpectedVersion(request == null ? null : request.expectedVersion());
        return idempotencyService.execute(
            "MATERIAL_RESERVATION_RELEASE", id, key, request, MaterialReservationResponse.class,
            () -> releaseInternal(id, request, key)
        );
    }

    private MaterialReservationResponse releaseInternal(
        UUID id,
        ReleaseReservationRequest request,
        String idempotencyKey
    ) {
        MaterialReservation reservation = lockReservation(id);
        requireVersion(reservation, request.expectedVersion());
        requireOpen(reservation);
        String oldSnapshot = snapshotSerializer.toJson(mapper.toResponse(reservation));
        BigDecimal outstanding = nvl(reservation.getReservedQuantity());
        BigDecimal quantity = request.quantity() == null ? outstanding : request.quantity();
        validateCommandQuantity(quantity, outstanding, "Release quantity exceeds reserved balance");
        StockBalance balance = lockBalance(reservation);
        ReservationStockMutationService.Mutation mutation = stockMutationService.release(balance, quantity);
        releaseReservedUnits(reservation, request, quantity);
        String fromStatus = reservation.getStatus();
        reservation.setReservedQuantity(outstanding.subtract(quantity));
        reservation.setReleasedQuantity(nvl(reservation.getReleasedQuantity()).add(quantity));
        reservation.setReleasedAt(now());
        reservation.setReleasedBy(username());
        if (reservation.getReservedQuantity().signum() == 0) {
            terminal(reservation, MaterialReservationStatus.RELEASED, request.comment(), null, idempotencyKey);
        } else {
            reservation.setStatus(MaterialReservationStatus.PARTIALLY_RELEASED.name());
        }
        return finishLifecycle(
            reservation, balance, "RELEASED", idempotencyKey, request.expectedVersion(), quantity,
            mutation, fromStatus, request.comment(), null, "MM_MATERIAL_RESERVATION_RELEASED", "RELEASE", oldSnapshot
        );
    }

    @Transactional
    public MaterialReservationResponse consume(UUID id, ConsumeRequest request, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        validateConsume(request);
        return idempotencyService.execute(
            "MATERIAL_RESERVATION_CONSUME", id, key, request, MaterialReservationResponse.class,
            () -> consumeInternal(id, request, key)
        );
    }

    private MaterialReservationResponse consumeInternal(UUID id, ConsumeRequest request, String idempotencyKey) {
        MaterialReservation reservation = lockReservation(id);
        requireVersion(reservation, request.expectedVersion());
        requireOpen(reservation);
        String oldSnapshot = snapshotSerializer.toJson(mapper.toResponse(reservation));
        BigDecimal outstanding = nvl(reservation.getReservedQuantity());
        validateCommandQuantity(request.quantity(), outstanding, "Consumption quantity exceeds reserved balance");
        StockBalance balance = lockBalance(reservation);
        ReservationStockMutationService.Mutation mutation = stockMutationService.consume(balance, request.quantity());
        String fromStatus = reservation.getStatus();
        reservation.setReservedQuantity(outstanding.subtract(request.quantity()));
        reservation.setConsumedQuantity(nvl(reservation.getConsumedQuantity()).add(request.quantity()));
        reservation.setConsumedAt(now());
        reservation.setConsumedBy(username());
        if (reservation.getReservedQuantity().signum() == 0) {
            terminal(reservation, MaterialReservationStatus.CONSUMED, request.reason(), request.evidenceReference(), idempotencyKey);
        } else {
            reservation.setStatus(MaterialReservationStatus.PARTIALLY_RELEASED.name());
        }
        return finishLifecycle(
            reservation, balance, "CONSUMED", idempotencyKey, request.expectedVersion(), request.quantity(),
            mutation, fromStatus, request.reason(), request.evidenceReference(),
            "MM_MATERIAL_RESERVATION_CONSUMED", "CONSUME", oldSnapshot
        );
    }

    /**
     * Converts the reserved bucket back to available immediately before the
     * stock-movement posting in the same parent transaction. The movement owns
     * the on-hand/value decrement and its immutable allocation evidence; this
     * command owns only the reservation lifecycle transition, preventing a
     * double decrement while preserving CONSUMED semantics.
     */
    @Transactional
    public MaterialReservationResponse consumeForStockPosting(
        UUID id,
        ConsumeRequest request,
        String idempotencyKey
    ) {
        String key = requireIdempotencyKey(idempotencyKey);
        validateConsume(request);
        return idempotencyService.execute(
            "MATERIAL_RESERVATION_CONSUME_FOR_STOCK_POSTING",
            id,
            key,
            request,
            MaterialReservationResponse.class,
            () -> consumeForStockPostingInternal(id, request, key)
        );
    }

    private MaterialReservationResponse consumeForStockPostingInternal(
        UUID id,
        ConsumeRequest request,
        String idempotencyKey
    ) {
        MaterialReservation reservation = lockReservation(id);
        requireVersion(reservation, request.expectedVersion());
        requireOpen(reservation);
        String oldSnapshot = snapshotSerializer.toJson(mapper.toResponse(reservation));
        BigDecimal outstanding = nvl(reservation.getReservedQuantity());
        validateCommandQuantity(
            request.quantity(), outstanding, "Consumption quantity exceeds reserved balance");
        StockBalance balance = lockBalance(reservation);
        ReservationStockMutationService.Mutation mutation =
            stockMutationService.release(balance, request.quantity());
        String fromStatus = reservation.getStatus();
        reservation.setReservedQuantity(outstanding.subtract(request.quantity()));
        reservation.setConsumedQuantity(
            nvl(reservation.getConsumedQuantity()).add(request.quantity()));
        reservation.setConsumedAt(now());
        reservation.setConsumedBy(username());
        if (reservation.getReservedQuantity().signum() == 0) {
            terminal(
                reservation,
                MaterialReservationStatus.CONSUMED,
                request.reason(),
                request.evidenceReference(),
                idempotencyKey
            );
        } else {
            reservation.setStatus(MaterialReservationStatus.PARTIALLY_RELEASED.name());
        }
        return finishLifecycle(
            reservation,
            balance,
            "CONSUMED_FOR_STOCK_POSTING",
            idempotencyKey,
            request.expectedVersion(),
            request.quantity(),
            mutation,
            fromStatus,
            request.reason(),
            request.evidenceReference(),
            "MM_MATERIAL_RESERVATION_CONSUMED",
            "CONSUME_FOR_STOCK_POSTING",
            oldSnapshot
        );
    }

    @Transactional
    public MaterialReservationResponse cancel(UUID id, CancelRequest request, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        validateTerminalCommand(request == null ? null : request.reason(), request == null ? null : request.expectedVersion());
        return idempotencyService.execute(
            "MATERIAL_RESERVATION_CANCEL", id, key, request, MaterialReservationResponse.class,
            () -> cancelInternal(id, request, key)
        );
    }

    private MaterialReservationResponse cancelInternal(UUID id, CancelRequest request, String idempotencyKey) {
        MaterialReservation reservation = lockReservation(id);
        requireVersion(reservation, request.expectedVersion());
        requireOpen(reservation);
        String oldSnapshot = snapshotSerializer.toJson(mapper.toResponse(reservation));
        BigDecimal quantity = nvl(reservation.getReservedQuantity());
        validateCommandQuantity(quantity, quantity, "Reservation has no outstanding quantity");
        StockBalance balance = lockBalance(reservation);
        ReservationStockMutationService.Mutation mutation = stockMutationService.release(balance, quantity);
        String fromStatus = reservation.getStatus();
        reservation.setReservedQuantity(BigDecimal.ZERO);
        reservation.setCancelledQuantity(nvl(reservation.getCancelledQuantity()).add(quantity));
        reservation.setCancelledAt(now());
        reservation.setCancelledBy(username());
        reservation.setCancellationReason(normalizeReason(request.reason()));
        terminal(reservation, MaterialReservationStatus.CANCELLED, request.reason(), request.evidenceReference(), idempotencyKey);
        return finishLifecycle(
            reservation, balance, "CANCELLED", idempotencyKey, request.expectedVersion(), quantity,
            mutation, fromStatus, request.reason(), request.evidenceReference(),
            "MM_MATERIAL_RESERVATION_CANCELLED", "CANCEL", oldSnapshot
        );
    }

    @Transactional
    public MaterialReservationResponse expire(UUID id, ExpireRequest request, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        validateTerminalCommand(request == null ? null : request.reason(), request == null ? null : request.expectedVersion());
        return idempotencyService.execute(
            "MATERIAL_RESERVATION_EXPIRE", id, key, request, MaterialReservationResponse.class,
            () -> expireInternal(id, request.expectedVersion(), request.reason(), request.evidenceReference(), key, false)
        );
    }

    private MaterialReservationResponse expireInternal(
        UUID id,
        Long expectedVersion,
        String reason,
        String evidenceReference,
        String idempotencyKey,
        boolean replaySafeBatch
    ) {
        MaterialReservation reservation = lockReservation(id);
        if (!OPEN_STATUSES.contains(reservation.getStatus())) {
            if (replaySafeBatch) {
                return mapper.toResponse(reservation);
            }
            throw terminalConflict(reservation);
        }
        requireCompleteStockKeySnapshot(reservation);
        if (expectedVersion != null) {
            requireVersion(reservation, expectedVersion);
        }
        if (reservation.getExpiresAt() == null || reservation.getExpiresAt().isAfter(now())) {
            throw new ConflictException("Reservation is not due for expiry");
        }
        String oldSnapshot = snapshotSerializer.toJson(mapper.toResponse(reservation));
        BigDecimal quantity = nvl(reservation.getReservedQuantity());
        validateCommandQuantity(quantity, quantity, "Reservation has no outstanding quantity");
        StockBalance balance = lockBalance(reservation);
        ReservationStockMutationService.Mutation mutation = stockMutationService.release(balance, quantity);
        String fromStatus = reservation.getStatus();
        reservation.setReservedQuantity(BigDecimal.ZERO);
        reservation.setExpiredQuantity(nvl(reservation.getExpiredQuantity()).add(quantity));
        reservation.setExpiredAt(now());
        reservation.setExpiredBy(username());
        reservation.setExpirationReason(normalizeReason(reason));
        terminal(reservation, MaterialReservationStatus.EXPIRED, reason, evidenceReference, idempotencyKey);
        return finishLifecycle(
            reservation, balance, "EXPIRED", idempotencyKey, expectedVersion, quantity,
            mutation, fromStatus, reason, evidenceReference, "MM_MATERIAL_RESERVATION_EXPIRED", "EXPIRE", oldSnapshot
        );
    }

    @Transactional
    public int expireDueCurrentScope(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        List<UUID> ids = repository.findDueIds(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), now(), PageRequest.of(0, limit));
        int expired = 0;
        for (UUID id : ids) {
            MaterialReservation current = repository.findForUpdate(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id).orElse(null);
            if (current == null || !OPEN_STATUSES.contains(current.getStatus())
                || current.getExpiresAt() == null || current.getExpiresAt().isAfter(now())) {
                continue;
            }
            String key = "clock-expiry-" + id + "-" + current.getExpiresAt();
            expireInternal(id, current.getVersion(), "Reservation validity elapsed", "SYSTEM_CLOCK", key, true);
            expired++;
        }
        return expired;
    }

    @Scheduled(cron = "${mm.material-reservations.expiry-cron:0 */10 * * * *}")
    @Transactional
    public void expireDueAcrossScopes() {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        try {
            List<MaterialReservationRepository.DueScopeProjection> scopes = repository.findDueScopes(now());
            for (MaterialReservationRepository.DueScopeProjection scope : scopes) {
                TenantContext.setTenant(scope.getTenantId());
                CompanyContext.setCompany(scope.getCompanyId());
                expireDueCurrentScope(500);
            }
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }

    private MaterialReservationResponse finishLifecycle(
        MaterialReservation reservation,
        StockBalance balance,
        String eventType,
        String idempotencyKey,
        Long expectedVersion,
        BigDecimal quantity,
        ReservationStockMutationService.Mutation mutation,
        String fromStatus,
        String reason,
        String evidenceReference,
        String integrationType,
        String auditAction,
        String oldSnapshot
    ) {
        stockBalanceRepository.saveAndFlush(balance);
        MaterialReservation saved = repository.saveAndFlush(reservation);
        syncDemandItemReservedQuantity(saved.getDemandPlanItemId(), quantity.negate());
        eventService.append(saved, balance, eventType, idempotencyKey, expectedVersion, quantity, mutation,
            fromStatus, reason, evidenceReference);
        integrationEventService.emit(integrationType, ENTITY_NAME, saved.getId(), Map.of(
            "reservationId", saved.getId(),
            "materialId", saved.getMaterialId(),
            "warehouseId", saved.getWarehouseId(),
            "quantity", quantity,
            "status", saved.getStatus(),
            "storageKey", stockKey(saved)
        ));
        MaterialReservationResponse response = mapper.toResponse(saved);
        auditService.log(ENTITY_NAME, saved.getId(), auditAction,
            "quantity=" + quantity + ", status=" + saved.getStatus(), oldSnapshot, response);
        return response;
    }

    @Transactional
    public MaterialReservationResponse reserveForDemandItem(UUID demandPlanItemId, DemandReservationRequest request) {
        if (request == null || request.warehouseId() == null) {
            throw new BadRequestException("warehouseId is required");
        }
        DemandPlanItem item = demandPlanItemRepository.findByIdAndIsDeletedFalse(demandPlanItemId)
            .filter(existing -> !existing.isDeleted()
                && TenantContext.currentTenant().equals(existing.getTenantId())
                && Objects.equals(CompanyContext.currentCompany(), existing.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Demand item not found: " + demandPlanItemId));
        if (!List.of(DemandPlanItemStatus.APPROVED.name(), DemandPlanItemStatus.RESERVED.name()).contains(item.getStatus())) {
            throw new BusinessException("Only approved or partially reserved demand item can be reserved");
        }
        BigDecimal remaining = nvl(item.getApprovedQuantity()).subtract(nvl(item.getReservedQuantity()));
        BigDecimal quantity = request.quantity() == null ? remaining : request.quantity();
        validateCommandQuantity(quantity, remaining, "Reservation quantity exceeds approved demand balance");
        MaterialReservationCreateRequest dto = new MaterialReservationCreateRequest();
        dto.setName("Резерв " + item.getName());
        dto.setMaterialId(item.getMaterialId());
        dto.setWarehouseId(request.warehouseId());
        dto.setStorageLocationId(request.storageLocationId());
        dto.setStockBatchId(request.stockBatchId());
        dto.setDemandPlanId(item.getDemandPlanId());
        dto.setDemandPlanItemId(item.getId());
        dto.setProjectCode(item.getProjectCode());
        dto.setRepairOrderCode(item.getRepairOrderCode());
        dto.setReservationType(reservationType(item.getDemandType()));
        dto.setPriority(item.getPriority());
        dto.setQuantity(quantity);
        dto.setRequiredDate(item.fulfillmentDate());
        dto.setReferenceType("DemandPlanItem");
        dto.setReferenceId(item.getId());
        String key = "demand-item-" + item.getId() + "-" + item.getVersion() + "-" + quantity.toPlainString();
        return create(dto, key);
    }

    @Transactional
    public MaterialReservationResponse update(UUID id, MaterialReservationUpdateRequest dto) {
        MaterialReservation reservation = lockReservation(id);
        throw genericMutationConflict(reservation, "update");
    }

    @Transactional
    public void softDelete(UUID id) {
        MaterialReservation reservation = lockReservation(id);
        throw genericMutationConflict(reservation, "delete");
    }

    @Transactional
    public MaterialReservationResponse restore(UUID id) {
        MaterialReservation entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        requireEntityScope(entity, id, false);
        if (TERMINAL_STATUSES.contains(entity.getStatus())) {
            throw genericMutationConflict(entity, "restore");
        }
        String oldSnapshot = snapshotSerializer.toJson(mapper.toResponse(entity));
        entity.restore();
        MaterialReservation saved = repository.saveAndFlush(entity);
        MaterialReservationResponse response = mapper.toResponse(saved);
        auditService.log(ENTITY_NAME, id, "RESTORE", entity.getCode(), oldSnapshot, response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<MaterialReservationResponse> search(MaterialReservationSearchRequest request, Pageable pageable) {
        SearchCriteria filter = roleDataScopeService.applyWarehouseScope(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(), scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(), filter.normalizedSearch(), filter.fromDateTime(), filter.toDateTime(),
            filter.status(), !filter.hasStatus(), filter.hasWarehouseIds() ? null : filter.warehouseId(),
            filter.warehouseIds(), !filter.hasWarehouseIds(), filter.materialIds(),
            !filter.hasMaterialIds(), filter.referenceId(), roleDataScopeService.isDepartmentWarehouseScoped(),
            roleDataScopeService.issueDepartmentId(), pageable
        ).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public MaterialReservationResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public List<MaterialReservationEventResponse> events(UUID id) {
        return eventService.list(findEntity(id));
    }

    protected MaterialReservation findEntity(UUID id) {
        MaterialReservation entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        requireEntityScope(entity, id, true);
        return entity;
    }

    private MaterialReservation lockReservation(UUID id) {
        MaterialReservation reservation = repository.findForUpdate(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        requireEntityScope(reservation, id, true);
        return reservation;
    }

    private void requireEntityScope(MaterialReservation entity, UUID id, boolean rejectDeleted) {
        if ((rejectDeleted && entity.isDeleted())
            || !TenantContext.currentTenant().equals(entity.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))
            || !roleDataScopeService.canAccessWarehouse(entity.getWarehouseId())
            || (roleDataScopeService.isDepartmentWarehouseScoped()
                && !roleDataScopeService.canReadIssueDepartment(reservationDepartment(entity)))) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
    }

    private UUID reservationDepartment(MaterialReservation reservation) {
        var plan = reservation.getDemandPlan();
        if (plan == null && reservation.getDemandPlanItem() != null) {
            plan = reservation.getDemandPlanItem().getDemandPlan();
        }
        return plan == null || plan.getDepartment() == null ? null : plan.getDepartment().getId();
    }

    private void initialize(MaterialReservation reservation) {
        if (reservation == null) {
            throw new BadRequestException("Reservation request is required");
        }
        materialCatalogGuard.requireActiveTmbMaterial(reservation.getMaterialId(), "materialId");
        reservation.setTenantId(TenantContext.currentTenant());
        reservation.setCompanyId(CompanyContext.currentCompany());
        reservation.setStatus(MaterialReservationStatus.ACTIVE.name());
        if (reservation.getCode() == null || reservation.getCode().isBlank()) {
            reservation.setCode("MATERIAL_RESERVATION-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        reservation.setName(BusinessDisplayNamePolicy.requireHumanName(
            "Material reservation", reservation.getName(), reservation.getCode()
        ));
        if (reservation.getMaterialId() == null || reservation.getWarehouseId() == null) {
            throw new BadRequestException("materialId and warehouseId are required");
        }
        if (!roleDataScopeService.canAccessWarehouse(reservation.getWarehouseId())) {
            throw new NotFoundException("Warehouse not found: " + reservation.getWarehouseId());
        }
        if (reservation.getReservationType() == null || reservation.getReservationType().isBlank()) {
            reservation.setReservationType(MaterialReservationType.MANUAL.name());
        }
        if (reservation.getPriority() == null || reservation.getPriority().isBlank()) {
            reservation.setPriority("NORMAL");
        }
        reservation.setQuantity(nvl(reservation.getQuantity()));
        if (reservation.getQuantity().signum() <= 0) {
            throw new BadRequestException("Reservation quantity must be positive");
        }
        if (reservation.getExpiresAt() != null && !reservation.getExpiresAt().isAfter(now())) {
            throw new BadRequestException("expiresAt must be in the future");
        }
        reservation.setReservedQuantity(reservation.getQuantity());
        reservation.setReleasedQuantity(BigDecimal.ZERO);
        reservation.setConsumedQuantity(BigDecimal.ZERO);
        reservation.setCancelledQuantity(BigDecimal.ZERO);
        reservation.setExpiredQuantity(BigDecimal.ZERO);
        reservation.setReservedBy(username());
    }

    private Material validateAndSnapshotStockKey(MaterialReservation reservation) {
        Material material = materialRepository.findMaterial360ByTenantAndId(
                TenantContext.currentTenant(), reservation.getMaterialId())
            .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new NotFoundException("Material not found: " + reservation.getMaterialId()));
        if (reservation.getStorageLocationId() != null) {
            var location = storageLocationRepository.findByIdAndIsDeletedFalse(reservation.getStorageLocationId())
                .filter(value -> TenantContext.currentTenant().equals(value.getTenantId())
                    && Objects.equals(CompanyContext.currentCompany(), value.getCompanyId())
                    && Objects.equals(reservation.getWarehouseId(), value.getWarehouseId()))
                .orElseThrow(() -> new BadRequestException("Storage location does not belong to the reservation warehouse"));
            reservation.setStorageLocationId(location.getId());
        }
        if (reservation.getStorageBinId() != null) {
            if (reservation.getStorageLocationId() == null) {
                throw new BadRequestException("storageLocationId is required when storageBinId is provided");
            }
            var bin = storageBinRepository.findByIdAndIsDeletedFalse(reservation.getStorageBinId())
                .filter(value -> TenantContext.currentTenant().equals(value.getTenantId())
                    && Objects.equals(CompanyContext.currentCompany(), value.getCompanyId())
                    && Objects.equals(reservation.getWarehouseId(), value.getWarehouseId())
                    && Objects.equals(reservation.getStorageLocationId(), value.getStorageLocationId()))
                .orElseThrow(() -> new BadRequestException("Storage bin does not belong to the reservation stock key"));
            reservation.setStorageBinId(bin.getId());
        }
        if (reservation.getStockBatchId() != null) {
            var batch = stockBatchRepository.findByIdAndIsDeletedFalse(reservation.getStockBatchId())
                .filter(value -> TenantContext.currentTenant().equals(value.getTenantId())
                    && Objects.equals(CompanyContext.currentCompany(), value.getCompanyId())
                    && Objects.equals(reservation.getWarehouseId(), value.getWarehouseId())
                    && Objects.equals(reservation.getMaterialId(), value.getMaterialId()))
                .orElseThrow(() -> new BadRequestException("Stock batch does not belong to the reservation stock key"));
            reservation.setStockBatchId(batch.getId());
        }
        if (material.getUnitOfMeasureId() == null || material.getUnitOfMeasure() == null) {
            throw new ConflictException("Material unit of measure must be configured before stock can be reserved");
        }
        String uomCode = firstNonBlank(
            material.getUnitOfMeasure().getCode(),
            material.getUnitOfMeasure().getMeasurementNameShort(),
            material.getUnitOfMeasure().getMeasurementNameShortRu(),
            material.getUnitOfMeasure().getName()
        );
        if (uomCode == null) {
            throw new ConflictException("Material unit of measure code is required before stock can be reserved");
        }
        reservation.setDispositionSnapshot(AVAILABLE_DISPOSITION);
        reservation.setUomIdSnapshot(material.getUnitOfMeasureId());
        reservation.setUomCodeSnapshot(uomCode);
        return material;
    }

    private void releaseReservedUnits(
        MaterialReservation reservation,
        ReleaseReservationRequest request,
        BigDecimal quantity
    ) {
        List<UUID> requested = request.materialUnitIds() == null ? List.of() : request.materialUnitIds();
        List<UUID> unitIds = requested.isEmpty()
            ? firstUnitIds(materialUnitEligibilityService.operationUnitIds(
                ENTITY_NAME,
                reservation.getId(),
                MaterialUnitOperationType.RESERVATION
            ), quantity)
            : requested;
        if (unitIds.isEmpty()) {
            return;
        }
        materialUnitEligibilityService.releaseReservation(
            reservation.getMaterialId(),
            reservation.getWarehouseId(),
            reservation.getStorageLocationId(),
            reservation.getStorageBinId(),
            quantity,
            unitIds,
            ENTITY_NAME,
            reservation.getId(),
            null
        );
    }

    private List<UUID> firstUnitIds(List<UUID> source, BigDecimal quantity) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        int count = quantity.stripTrailingZeros().intValueExact();
        return new ArrayList<>(source.subList(0, Math.min(count, source.size())));
    }

    private StockBalance lockBalance(MaterialReservation reservation) {
        inventoryFreezeGuard.ensureStockKeyAllowed(reservation.getCompanyId(),reservation.getWarehouseId(),reservation.getStorageLocationId(),reservation.getStorageBinId(),reservation.getMaterialId());
        return stockBalanceRepository.findCompanyBalanceForUpdate(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), reservation.getMaterialId(),
            reservation.getWarehouseId(), reservation.getStorageLocationId() == null, reservation.getStorageLocationId(),
            reservation.getStorageBinId() == null, reservation.getStorageBinId(),
            reservation.getStockBatchId() == null, reservation.getStockBatchId()
        ).orElseThrow(() -> new BusinessException("Stock balance not found for the complete reservation stock key"));
    }

    private void terminal(
        MaterialReservation reservation,
        MaterialReservationStatus status,
        String reason,
        String evidenceReference,
        String idempotencyKey
    ) {
        reservation.setStatus(status.name());
        reservation.setTerminalAt(now());
        reservation.setTerminalBy(username());
        reservation.setTerminalReason(normalizeReasonOrDefault(reason, status.name()));
        reservation.setTerminalEvidenceReference(blankToNull(evidenceReference));
        reservation.setTerminalIdempotencyKey(idempotencyKey);
    }

    private void requireOpen(MaterialReservation reservation) {
        if (!OPEN_STATUSES.contains(reservation.getStatus())) {
            throw terminalConflict(reservation);
        }
        requireCompleteStockKeySnapshot(reservation);
    }

    private void requireCompleteStockKeySnapshot(MaterialReservation reservation) {
        if (reservation.getDispositionSnapshot() == null
            || reservation.getUomIdSnapshot() == null
            || reservation.getUomCodeSnapshot() == null
            || reservation.getUomCodeSnapshot().isBlank()) {
            throw new ConflictException(
                "Reservation stock-key snapshots are incomplete; reconcile disposition and unit of measure before execution");
        }
    }

    private ConflictException terminalConflict(MaterialReservation reservation) {
        return new ConflictException("Reservation " + reservation.getId() + " is immutable in status " + reservation.getStatus());
    }

    private ConflictException genericMutationConflict(MaterialReservation reservation, String operation) {
        return new ConflictException("Reservation lifecycle does not allow generic " + operation
            + " in status " + reservation.getStatus() + "; use an explicit lifecycle command");
    }

    private void requireVersion(MaterialReservation reservation, Long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (!Objects.equals(expectedVersion, reservation.getVersion())) {
            throw new ConflictException("Material reservation version conflict: expected=" + expectedVersion
                + ", actual=" + reservation.getVersion());
        }
    }

    private void requireExpectedVersion(Long expectedVersion) {
        if (expectedVersion == null) {
            throw new BadRequestException("expectedVersion is required");
        }
    }

    private void validateConsume(ConsumeRequest request) {
        if (request == null) {
            throw new BadRequestException("Consumption request is required");
        }
        validateTerminalCommand(request.reason(), request.expectedVersion());
        if (request.quantity() == null || request.quantity().signum() <= 0) {
            throw new BadRequestException("Consumption quantity must be positive");
        }
    }

    private void validateTerminalCommand(String reason, Long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        normalizeReason(reason);
    }

    private void validateCommandQuantity(BigDecimal quantity, BigDecimal outstanding, String message) {
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(outstanding) > 0) {
            throw new BadRequestException(message);
        }
    }

    private void syncDemandItemReservedQuantity(UUID demandPlanItemId, BigDecimal delta) {
        if (demandPlanItemId == null || delta == null || delta.signum() == 0) {
            return;
        }
        DemandPlanItem item = demandPlanItemRepository.findByIdAndIsDeletedFalse(demandPlanItemId)
            .filter(existing -> !existing.isDeleted()
                && TenantContext.currentTenant().equals(existing.getTenantId())
                && Objects.equals(CompanyContext.currentCompany(), existing.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Demand item not found: " + demandPlanItemId));
        BigDecimal nextReserved = nvl(item.getReservedQuantity()).add(delta);
        if (nextReserved.signum() < 0 || nextReserved.compareTo(nvl(item.getApprovedQuantity())) > 0) {
            throw new BusinessException("Demand item reserved quantity is inconsistent");
        }
        item.setReservedQuantity(nextReserved);
        if (!DemandPlanItemStatus.CONVERTED_TO_PR.name().equals(item.getStatus())) {
            item.setStatus(nextReserved.signum() == 0
                ? DemandPlanItemStatus.APPROVED.name()
                : DemandPlanItemStatus.RESERVED.name());
        }
        demandPlanItemRepository.save(item);
        demandPlanItemService.recalculatePlanTotals(item.getDemandPlanId());
    }

    private String reservationType(String demandType) {
        if (DemandType.PROJECT.name().equals(demandType) || DemandType.CAPEX.name().equals(demandType)) {
            return MaterialReservationType.PROJECT.name();
        }
        if (DemandType.REPAIR.name().equals(demandType)) {
            return MaterialReservationType.REPAIR.name();
        }
        return MaterialReservationType.DEMAND_PLAN.name();
    }

    private String requireIdempotencyKey(String value) {
        String key = blankToNull(value);
        if (key == null) {
            throw new BadRequestException("Idempotency-Key is required");
        }
        if (key.length() > 160) {
            throw new BadRequestException("Idempotency-Key must not exceed 160 characters");
        }
        return key;
    }

    private String normalizeReason(String value) {
        String reason = blankToNull(value);
        if (reason == null) {
            throw new BadRequestException("reason is required");
        }
        if (reason.length() > 500) {
            throw new BadRequestException("reason must not exceed 500 characters");
        }
        return reason;
    }

    private String normalizeReasonOrDefault(String value, String fallback) {
        String reason = blankToNull(value);
        return reason == null ? fallback : normalizeReason(reason);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String username() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stockKey(MaterialReservation reservation) {
        return reservation.getMaterialId() + "/" + reservation.getWarehouseId() + "/"
            + Objects.toString(reservation.getStorageLocationId(), "-") + "/"
            + Objects.toString(reservation.getStorageBinId(), "-") + "/"
            + Objects.toString(reservation.getStockBatchId(), "-") + "/"
            + reservation.getDispositionSnapshot() + "/" + reservation.getUomCodeSnapshot();
    }

    private String reference(MaterialReservation reservation) {
        return reservation.getReferenceType() + ":" + Objects.toString(reservation.getReferenceId(), "-");
    }

    protected boolean companyScoped() {
        return true;
    }
}

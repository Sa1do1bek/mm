package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialReservation;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.MaterialReservationStatus;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.MaterialReservationMapper;
import com.enterprise.mm.payload.DemandManagementDtos.ReleaseReservationRequest;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.CancelRequest;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.ConsumeRequest;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.ExpireRequest;
import com.enterprise.mm.payload.MaterialReservationCreateRequest;
import com.enterprise.mm.payload.MaterialReservationResponse;
import com.enterprise.mm.payload.MaterialReservationUpdateRequest;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.enterprise.mm.repository.MaterialReservationRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaterialReservationSafetyTest {
    @Mock private MaterialReservationRepository repository;
    @Mock private MaterialReservationMapper mapper;
    @Mock private StockBalanceRepository stockBalanceRepository;
    @Mock private DemandPlanItemRepository demandPlanItemRepository;
    @Mock private DemandPlanItemService demandPlanItemService;
    @Mock private MaterialCatalogGuard materialCatalogGuard;
    @Mock private MaterialRepository materialRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private StorageBinRepository storageBinRepository;
    @Mock private StockBatchRepository stockBatchRepository;
    @Mock private MaterialReservationEventService eventService;
    @Mock private MaterialUnitEligibilityService materialUnitEligibilityService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer snapshotSerializer;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private IdempotencyRecordRepository idempotencyRecordRepository;

    private final AtomicReference<IdempotencyRecord> storedRecord = new AtomicReference<>();
    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("material-reservation-safety-test");
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void directReservationLookupHidesWarehouseOutsideAssignedScope() {
        MaterialReservation reservation = reservation();
        when(repository.findByIdAndIsDeletedFalse(reservation.getId())).thenReturn(Optional.of(reservation));
        when(roleDataScopeService.canAccessWarehouse(reservation.getWarehouseId())).thenReturn(false);

        assertThatThrownBy(() -> service().get(reservation.getId()))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("MaterialReservation not found");
        verify(mapper, never()).toResponse(any(MaterialReservation.class));
    }

    @Test
    void repeatedReleaseKeyReturnsStoredResultWithoutReleasingStockAgain() {
        MaterialReservation reservation = reservation();
        StockBalance balance = new StockBalance();
        balance.setId(UUID.randomUUID());
        balance.setQuantity(BigDecimal.TEN);
        balance.setAvailableQuantity(BigDecimal.valueOf(5));
        balance.setReservedQuantity(BigDecimal.valueOf(5));
        balance.setTotalValue(BigDecimal.valueOf(100));
        when(idempotencyRecordRepository.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            anyString(), any(UUID.class), anyString(), any(UUID.class), anyString()
        )).thenAnswer(invocation -> Optional.ofNullable(storedRecord.get()));
        when(idempotencyRecordRepository.save(any(IdempotencyRecord.class))).thenAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);
            storedRecord.set(record);
            return record;
        });
        when(repository.findForUpdate(TenantContext.currentTenant(), companyId, reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(roleDataScopeService.canAccessWarehouse(reservation.getWarehouseId())).thenReturn(true);
        when(stockBalanceRepository.findCompanyBalanceForUpdate(
            TenantContext.currentTenant(),
            companyId,
            reservation.getMaterialId(),
            reservation.getWarehouseId(),
            true,
            null,
            true,
            null,
            true,
            null
        )).thenReturn(Optional.of(balance));
        when(repository.saveAndFlush(reservation)).thenReturn(reservation);
        when(mapper.toResponse(reservation)).thenAnswer(invocation -> response(reservation));

        ReleaseReservationRequest request = new ReleaseReservationRequest(BigDecimal.valueOf(2), "Partial release", 4L);
        MaterialReservationResponse first = service().release(reservation.getId(), request, "release-key-1");
        MaterialReservationResponse replay = service().release(reservation.getId(), request, "release-key-1");

        assertThat(first.getReservedQuantity()).isEqualByComparingTo("3");
        assertThat(replay.getReservedQuantity()).isEqualByComparingTo("3");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("7");
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("3");
        verify(eventService, times(1)).append(
            any(MaterialReservation.class), any(StockBalance.class), anyString(), anyString(),
            any(Long.class), any(BigDecimal.class), any(), anyString(), any(), any());
        verify(idempotencyRecordRepository, times(1)).save(any(IdempotencyRecord.class));
    }

    @Test
    void consumeEndsReservationAndKeepsAvailableNetOfReservation() {
        MaterialReservation reservation = reservation();
        StockBalance balance = balance();
        stubLifecycle(reservation, balance);

        MaterialReservationResponse response = service().consume(
            reservation.getId(),
            new ConsumeRequest(BigDecimal.valueOf(5), "Issued to maintenance", 4L, "ISSUE-100"),
            "consume-key-1"
        );

        assertThat(response.getStatus()).isEqualTo(MaterialReservationStatus.CONSUMED.name());
        assertThat(reservation.getReservedQuantity()).isZero();
        assertThat(reservation.getConsumedQuantity()).isEqualByComparingTo("5");
        assertThat(reservation.getTerminalReason()).isEqualTo("Issued to maintenance");
        assertThat(reservation.getTerminalEvidenceReference()).isEqualTo("ISSUE-100");
        assertThat(balance.getQuantity()).isEqualByComparingTo("5");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("5");
        assertThat(balance.getReservedQuantity()).isZero();
        assertThat(balance.getTotalValue()).isEqualByComparingTo("50");
    }

    @Test
    void stockPostingConsumptionTransitionsLifecycleButLeavesOnHandForMovementLedger() {
        MaterialReservation reservation = reservation();
        StockBalance balance = balance();
        stubLifecycle(reservation, balance);

        MaterialReservationResponse response = service().consumeForStockPosting(
            reservation.getId(),
            new ConsumeRequest(BigDecimal.valueOf(5), "Issued by stock movement", 4L, "ISSUE-101"),
            "consume-for-posting-key-1"
        );

        assertThat(response.getStatus()).isEqualTo(MaterialReservationStatus.CONSUMED.name());
        assertThat(reservation.getReservedQuantity()).isZero();
        assertThat(reservation.getConsumedQuantity()).isEqualByComparingTo("5");
        assertThat(balance.getQuantity()).isEqualByComparingTo("10");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("10");
        assertThat(balance.getReservedQuantity()).isZero();
        assertThat(balance.getTotalValue()).isEqualByComparingTo("100");
    }

    @Test
    void cancelReleasesAllOutstandingStockAndWritesTerminalEvidence() {
        MaterialReservation reservation = reservation();
        StockBalance balance = balance();
        stubLifecycle(reservation, balance);

        service().cancel(
            reservation.getId(),
            new CancelRequest("Work order cancelled", 4L, "WO-9"),
            "cancel-key-1"
        );

        assertThat(reservation.getStatus()).isEqualTo(MaterialReservationStatus.CANCELLED.name());
        assertThat(reservation.getReservedQuantity()).isZero();
        assertThat(reservation.getCancelledQuantity()).isEqualByComparingTo("5");
        assertThat(balance.getQuantity()).isEqualByComparingTo("10");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("10");
        assertThat(balance.getReservedQuantity()).isZero();
    }

    @Test
    void expiryIsControlledByInjectedClockAndTerminalStateCannotBeMutated() {
        MaterialReservation reservation = reservation();
        reservation.setExpiresAt(LocalDateTime.of(2026, 7, 16, 1, 0));
        StockBalance balance = balance();
        stubLifecycle(reservation, balance);

        assertThatThrownBy(() -> service().expire(
            reservation.getId(), new ExpireRequest("Expired", 4L, "CLOCK"), "expire-too-early"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("not due");

        reservation.setExpiresAt(LocalDateTime.of(2026, 7, 15, 23, 59));
        service().expire(
            reservation.getId(), new ExpireRequest("Validity elapsed", 4L, "CLOCK"), "expire-key-1");

        assertThat(reservation.getStatus()).isEqualTo(MaterialReservationStatus.EXPIRED.name());
        assertThat(reservation.getExpiredQuantity()).isEqualByComparingTo("5");
        assertThatThrownBy(() -> service().consume(
            reservation.getId(), new ConsumeRequest(BigDecimal.ONE, "late", 4L, "ISSUE"), "late-consume"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("immutable");
    }

    @Test
    void genericCrudCannotMutateLifecycleOrTerminalEvidence() {
        MaterialReservation reservation = reservation();
        when(repository.findForUpdate(TenantContext.currentTenant(), companyId, reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(roleDataScopeService.canAccessWarehouse(reservation.getWarehouseId())).thenReturn(true);

        assertThatThrownBy(() -> service().update(reservation.getId(), new MaterialReservationUpdateRequest()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("explicit lifecycle command");
        assertThatThrownBy(() -> service().softDelete(reservation.getId()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("explicit lifecycle command");

        reservation.setStatus(MaterialReservationStatus.CONSUMED.name());
        when(repository.findAnyById(reservation.getId())).thenReturn(Optional.of(reservation));
        assertThatThrownBy(() -> service().restore(reservation.getId()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("explicit lifecycle command");
    }

    @Test
    void repeatedCreateKeyReservesCompleteStockKeyOnlyOnce() {
        MaterialReservationCreateRequest request = new MaterialReservationCreateRequest();
        request.setName("Bearing reservation");
        request.setMaterialId(UUID.randomUUID());
        request.setWarehouseId(UUID.randomUUID());
        request.setQuantity(BigDecimal.valueOf(5));
        Material material = new Material();
        material.setId(request.getMaterialId());
        material.setTenantId(TenantContext.currentTenant());
        material.setCompanyId(companyId);
        UnitOfMeasure uom = new UnitOfMeasure();
        uom.setId(UUID.randomUUID());
        uom.setCode("PCS");
        material.setUnitOfMeasureId(uom.getId());
        material.setUnitOfMeasure(uom);
        StockBalance balance = balance();
        MaterialReservationMapper realMapper = new MaterialReservationMapper(
            Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));
        when(idempotencyRecordRepository.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            anyString(), any(UUID.class), anyString(), any(UUID.class), anyString()
        )).thenAnswer(invocation -> Optional.ofNullable(storedRecord.get()));
        when(idempotencyRecordRepository.save(any(IdempotencyRecord.class))).thenAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);
            storedRecord.set(record);
            return record;
        });
        when(roleDataScopeService.canAccessWarehouse(request.getWarehouseId())).thenReturn(true);
        when(materialRepository.findMaterial360ByTenantAndId(TenantContext.currentTenant(), request.getMaterialId()))
            .thenReturn(Optional.of(material));
        when(stockBalanceRepository.findCompanyBalanceForUpdate(
            TenantContext.currentTenant(), companyId, request.getMaterialId(), request.getWarehouseId(),
            true, null, true, null, true, null
        )).thenReturn(Optional.of(balance));
        when(repository.saveAndFlush(any(MaterialReservation.class))).thenAnswer(invocation -> {
            MaterialReservation saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setVersion(0L);
            return saved;
        });

        MaterialReservationService service = service(realMapper);
        MaterialReservationResponse first = service.create(request, "create-key-1");
        MaterialReservationResponse replay = service.create(request, "create-key-1");

        assertThat(first.getId()).isEqualTo(replay.getId());
        assertThat(first.getDispositionSnapshot()).isEqualTo("AVAILABLE");
        assertThat(first.getUomIdSnapshot()).isEqualTo(uom.getId());
        assertThat(first.getUomCodeSnapshot()).isEqualTo("PCS");
        assertThat(balance.getAvailableQuantity()).isZero();
        assertThat(balance.getReservedQuantity()).isEqualByComparingTo("10");
        verify(repository, times(1)).saveAndFlush(any(MaterialReservation.class));
        verify(eventService, times(1)).append(
            any(MaterialReservation.class), any(StockBalance.class), anyString(), anyString(),
            any(), any(BigDecimal.class), any(), any(), any(), any());
    }

    @Test
    void serializedCreateReservesExactMaterialUnits() {
        MaterialReservationCreateRequest request = new MaterialReservationCreateRequest();
        request.setName("Serialized reservation");
        request.setMaterialId(UUID.randomUUID());
        request.setWarehouseId(UUID.randomUUID());
        request.setQuantity(BigDecimal.valueOf(2));
        UUID unitA = UUID.randomUUID();
        UUID unitB = UUID.randomUUID();
        request.setMaterialUnitIds(java.util.List.of(unitA, unitB));
        Material material = material(request.getMaterialId(), true);
        StockBalance balance = balance();
        when(roleDataScopeService.canAccessWarehouse(request.getWarehouseId())).thenReturn(true);
        when(materialRepository.findMaterial360ByTenantAndId(TenantContext.currentTenant(), request.getMaterialId()))
            .thenReturn(Optional.of(material));
        when(stockBalanceRepository.findCompanyBalanceForUpdate(
            TenantContext.currentTenant(), companyId, request.getMaterialId(), request.getWarehouseId(),
            true, null, true, null, true, null
        )).thenReturn(Optional.of(balance));
        when(repository.saveAndFlush(any(MaterialReservation.class))).thenAnswer(invocation -> {
            MaterialReservation saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setVersion(0L);
            return saved;
        });
        when(materialUnitEligibilityService.reserve(
            org.mockito.ArgumentMatchers.eq(request.getMaterialId()),
            org.mockito.ArgumentMatchers.eq(request.getWarehouseId()),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(2)),
            org.mockito.ArgumentMatchers.eq(java.util.List.of(unitA, unitB)),
            org.mockito.ArgumentMatchers.eq("MaterialReservation"),
            any(UUID.class),
            org.mockito.ArgumentMatchers.isNull()
        )).thenReturn(java.util.List.of(materialUnit(unitA), materialUnit(unitB)));

        MaterialReservationResponse response = service(realReservationMapper()).create(request);

        assertThat(response.getReservedMaterialUnitIds()).containsExactly(unitA, unitB);
        verify(materialUnitEligibilityService).reserve(
            request.getMaterialId(),
            request.getWarehouseId(),
            null,
            null,
            BigDecimal.valueOf(2),
            java.util.List.of(unitA, unitB),
            "MaterialReservation",
            response.getId(),
            null
        );
    }

    @Test
    void serializedCreateRequiresUnitSelection() {
        MaterialReservationCreateRequest request = new MaterialReservationCreateRequest();
        request.setName("Serialized reservation");
        request.setMaterialId(UUID.randomUUID());
        request.setWarehouseId(UUID.randomUUID());
        request.setQuantity(BigDecimal.ONE);
        Material material = material(request.getMaterialId(), true);
        when(roleDataScopeService.canAccessWarehouse(request.getWarehouseId())).thenReturn(true);
        when(materialRepository.findMaterial360ByTenantAndId(TenantContext.currentTenant(), request.getMaterialId()))
            .thenReturn(Optional.of(material));

        assertThatThrownBy(() -> service(realReservationMapper()).create(request))
            .hasMessageContaining("SERIAL_UNIT_SELECTION_REQUIRED");
    }

    @Test
    void serializedReleaseRestoresExactReservedUnits() {
        MaterialReservation reservation = reservation();
        UUID unitA = UUID.randomUUID();
        UUID unitB = UUID.randomUUID();
        StockBalance balance = balance();
        stubLifecycle(reservation, balance);
        when(materialUnitEligibilityService.operationUnitIds(
            "MaterialReservation",
            reservation.getId(),
            com.enterprise.mm.enums.MaterialUnitOperationType.RESERVATION
        )).thenReturn(java.util.List.of(unitA, unitB));

        service().release(
            reservation.getId(),
            new ReleaseReservationRequest(BigDecimal.valueOf(2), "release serials", 4L),
            "serialized-release"
        );

        verify(materialUnitEligibilityService).releaseReservation(
            reservation.getMaterialId(),
            reservation.getWarehouseId(),
            reservation.getStorageLocationId(),
            reservation.getStorageBinId(),
            BigDecimal.valueOf(2),
            java.util.List.of(unitA, unitB),
            "MaterialReservation",
            reservation.getId(),
            null
        );
    }

    private MaterialReservationService service() {
        return service(mapper);
    }

    private MaterialReservationMapper realReservationMapper() {
        return new MaterialReservationMapper(
            Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));
    }

    private MaterialReservationService service(MaterialReservationMapper selectedMapper) {
        return new MaterialReservationService(
            org.mockito.Mockito.mock(InventoryFreezeGuardService.class), repository,
            selectedMapper,
            stockBalanceRepository,
            demandPlanItemRepository,
            demandPlanItemService,
            materialCatalogGuard,
            integrationEventService,
            auditService,
            snapshotSerializer,
            roleDataScopeService,
            new IdempotencyService(idempotencyRecordRepository, new ObjectMapper().findAndRegisterModules()),
            materialRepository,
            storageLocationRepository,
            storageBinRepository,
            stockBatchRepository,
            new ReservationStockMutationService(),
            eventService,
            materialUnitEligibilityService,
            Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private Material material(UUID id, boolean serialized) {
        Material material = new Material();
        material.setId(id);
        material.setTenantId(TenantContext.currentTenant());
        material.setCompanyId(companyId);
        material.setSerialTrackingPolicy(serialized
            ? com.enterprise.mm.enums.SerialTrackingPolicy.REQUIRED
            : com.enterprise.mm.enums.SerialTrackingPolicy.NONE);
        UnitOfMeasure uom = new UnitOfMeasure();
        uom.setId(UUID.randomUUID());
        uom.setCode("PCS");
        material.setUnitOfMeasureId(uom.getId());
        material.setUnitOfMeasure(uom);
        return material;
    }

    private MaterialUnit materialUnit(UUID id) {
        MaterialUnit unit = new MaterialUnit();
        unit.setId(id);
        return unit;
    }

    private void stubLifecycle(MaterialReservation reservation, StockBalance balance) {
        when(repository.findForUpdate(TenantContext.currentTenant(), companyId, reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(roleDataScopeService.canAccessWarehouse(reservation.getWarehouseId())).thenReturn(true);
        when(stockBalanceRepository.findCompanyBalanceForUpdate(
            TenantContext.currentTenant(), companyId, reservation.getMaterialId(), reservation.getWarehouseId(),
            true, null, true, null, true, null
        )).thenReturn(Optional.of(balance));
        when(repository.saveAndFlush(reservation)).thenReturn(reservation);
        when(mapper.toResponse(reservation)).thenAnswer(invocation -> response(reservation));
    }

    private StockBalance balance() {
        StockBalance balance = new StockBalance();
        balance.setId(UUID.randomUUID());
        balance.setQuantity(BigDecimal.TEN);
        balance.setAvailableQuantity(BigDecimal.valueOf(5));
        balance.setReservedQuantity(BigDecimal.valueOf(5));
        balance.setTotalValue(BigDecimal.valueOf(100));
        return balance;
    }

    private MaterialReservation reservation() {
        MaterialReservation reservation = new MaterialReservation();
        reservation.setId(UUID.randomUUID());
        reservation.setTenantId(TenantContext.currentTenant());
        reservation.setCompanyId(companyId);
        reservation.setCode("RSV-1001");
        reservation.setName("Bearing reservation");
        reservation.setStatus(MaterialReservationStatus.ACTIVE.name());
        reservation.setVersion(4L);
        reservation.setMaterialId(UUID.randomUUID());
        reservation.setWarehouseId(UUID.randomUUID());
        reservation.setDispositionSnapshot("AVAILABLE");
        reservation.setUomIdSnapshot(UUID.randomUUID());
        reservation.setUomCodeSnapshot("PCS");
        reservation.setQuantity(BigDecimal.valueOf(5));
        reservation.setReservedQuantity(BigDecimal.valueOf(5));
        reservation.setReleasedQuantity(BigDecimal.ZERO);
        return reservation;
    }

    private MaterialReservationResponse response(MaterialReservation reservation) {
        MaterialReservationResponse response = new MaterialReservationResponse();
        response.setId(reservation.getId());
        response.setCode(reservation.getCode());
        response.setName(reservation.getName());
        response.setStatus(reservation.getStatus());
        response.setVersion(reservation.getVersion());
        response.setReservedQuantity(reservation.getReservedQuantity());
        response.setReleasedQuantity(reservation.getReleasedQuantity());
        response.setConsumedQuantity(reservation.getConsumedQuantity());
        response.setCancelledQuantity(reservation.getCancelledQuantity());
        response.setExpiredQuantity(reservation.getExpiredQuantity());
        return response;
    }
}

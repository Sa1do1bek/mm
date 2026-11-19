package com.enterprise.mm.warehouse.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HistoricalActivationResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HistoricalHoldingActivationCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P3 AT04 Acceptance Test:
 * Verifies that Account 014 historical activation creates direct holdings,
 * never replays old source issues (stock balances and movements are never touched),
 * and is strictly idempotent.
 */
class HistoricalHoldingActivationTest {

    private static final String TENANT = "tenant-navoiy";
    private static final UUID COMPANY = UUID.randomUUID();

    private WarehouseHoldingRepository holdingRepository;
    private WarehouseHoldingHandoverRepository handoverRepository;
    private StockBalanceRepository stockBalanceRepository;
    private StockMovementRepository stockMovementRepository;
    private IdempotencyService idempotencyService;
    private WarehouseHoldingService holdingService;

    private final Map<UUID, WarehouseHolding> holdingStore = new HashMap<>();
    private final Map<UUID, WarehouseHoldingHandover> handoverStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        holdingStore.clear();
        handoverStore.clear();

        holdingRepository = mock(WarehouseHoldingRepository.class);
        handoverRepository = mock(WarehouseHoldingHandoverRepository.class);
        stockBalanceRepository = mock(StockBalanceRepository.class);
        stockMovementRepository = mock(StockMovementRepository.class);
        idempotencyService = mock(IdempotencyService.class);

        when(handoverRepository.save(any(WarehouseHoldingHandover.class))).thenAnswer(inv -> {
            WarehouseHoldingHandover ho = inv.getArgument(0);
            if (ho.getId() == null) {
                ho.setId(UUID.randomUUID());
            }
            ho.setTenantId(TENANT);
            ho.setCompanyId(COMPANY);
            handoverStore.put(ho.getId(), ho);
            return ho;
        });

        when(handoverRepository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(inv -> {
                String key = inv.getArgument(2);
                return handoverStore.values().stream()
                    .filter(ho -> key != null && key.equals(ho.getIdempotencyKey()))
                    .findFirst();
            });

        when(holdingRepository.save(any(WarehouseHolding.class))).thenAnswer(inv -> {
            WarehouseHolding h = inv.getArgument(0);
            if (h.getId() == null) {
                h.setId(UUID.randomUUID());
            }
            h.setTenantId(TENANT);
            h.setCompanyId(COMPANY);
            holdingStore.put(h.getId(), h);
            return h;
        });

        when(holdingRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.ofNullable(holdingStore.get(id));
        });

        when(holdingRepository.findByTenantIdAndCompanyIdAndOriginReferenceAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(inv -> {
                String ref = inv.getArgument(2);
                return holdingStore.values().stream()
                    .filter(h -> ref != null && ref.equals(h.getOriginReference()))
                    .toList();
            });

        when(holdingRepository.findFirstByTenantIdAndCompanyIdAndMaterialIdAndDepartmentIdAndEmployeeIdAndAccountCodeAndIsDeletedFalse(
            any(), any(), any(), any(), any(), any()
        )).thenAnswer(inv -> {
            UUID matId = inv.getArgument(2);
            UUID deptId = inv.getArgument(3);
            UUID empId = inv.getArgument(4);
            String acct = inv.getArgument(5);
            return holdingStore.values().stream()
                .filter(h -> (matId == null || matId.equals(h.getMaterialId()))
                    && (deptId == null || deptId.equals(h.getDepartmentId()))
                    && (empId == null || empId.equals(h.getEmployeeId()))
                    && (acct == null || acct.equals(h.getAccountCode())))
                .findFirst();
        });

        when(idempotencyService.execute(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            if (inv.getArgument(5) instanceof Supplier<?> s) {
                return s.get();
            }
            return null;
        });

        holdingService = new WarehouseHoldingService(
            holdingRepository,
            handoverRepository,
            idempotencyService,
            stockBalanceRepository,
            stockMovementRepository
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P3 AT04.1: Historical account 014 activation creates direct active holding without warehouse stock issue")
    void activatingHistoricalHoldingCreatesDirectActiveHoldingWithoutWarehouseIssue() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        var command = new HistoricalHoldingActivationCommand(
            "ACT-014-001",
            materialId,
            departmentId,
            employeeId,
            new BigDecimal("25.0000000"),
            new BigDecimal("120.500000"),
            new BigDecimal("3012.5000"),
            "PCS",
            "1C-SRC-014-998811",
            LocalDate.of(2026, 10, 1)
        );

        HistoricalActivationResult result = holdingService.activateHistoricalHolding(command);

        assertThat(result).isNotNull();
        assertThat(result.replayed()).isFalse();
        assertThat(result.originType()).isEqualTo(HoldingOriginType.HISTORICAL_1C_OPENING);
        assertThat(result.activeQuantity()).isEqualByComparingTo("25.0000000");
        assertThat(result.pendingQuantity()).isEqualByComparingTo("0.0000000");

        // Verify warehouse operational stock balances and movement tables are NEVER touched
        verify(stockBalanceRepository, never()).save(any());
        verify(stockMovementRepository, never()).save(any());

        // Verify holding stored with 014 account and active status
        WarehouseHolding holding = holdingStore.get(result.holdingId());
        assertThat(holding).isNotNull();
        assertThat(holding.getAccountCode()).isEqualTo("014");
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("25.0000000");
        assertThat(holding.getPendingQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(holding.getHoldingStatus()).isEqualTo(HoldingStatus.ACTIVE);
        assertThat(holding.getOriginType()).isEqualTo(HoldingOriginType.HISTORICAL_1C_OPENING);
    }

    @Test
    @DisplayName("P3 AT04.2: Activation is strictly idempotent and repeated execution never duplicates holding")
    void activationIsStrictlyIdempotentAndNeverReplaysOldSourceIssue() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        var command = new HistoricalHoldingActivationCommand(
            "ACT-014-IDEMPOTENT-001",
            materialId,
            departmentId,
            employeeId,
            new BigDecimal("40.0000000"),
            new BigDecimal("80.000000"),
            new BigDecimal("3200.0000"),
            "SET",
            "1C-SRC-014-554433",
            LocalDate.of(2026, 10, 1)
        );

        HistoricalActivationResult firstRun = holdingService.activateHistoricalHolding(command);
        assertThat(firstRun.replayed()).isFalse();
        assertThat(firstRun.activeQuantity()).isEqualByComparingTo("40.0000000");
        assertThat(holdingStore).hasSize(1);

        // Repeated run with exact same idempotency key
        HistoricalActivationResult secondRun = holdingService.activateHistoricalHolding(command);
        assertThat(secondRun.replayed()).isTrue();
        assertThat(secondRun.holdingId()).isEqualTo(firstRun.holdingId());
        assertThat(secondRun.activeQuantity()).isEqualByComparingTo("40.0000000");

        // Total holdings store size remains exactly 1, active quantity is NOT doubled to 80
        assertThat(holdingStore).hasSize(1);
        WarehouseHolding holding = holdingStore.get(firstRun.holdingId());
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("40.0000000");

        // Still never touches operational stock
        verify(stockBalanceRepository, never()).save(any());
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    @DisplayName("P3 AT04.3: Conflicting payload with differing parameters under same idempotency key throws ConflictException")
    void conflictingPayloadUnderSameIdempotencyKeyThrowsConflict() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        var originalCommand = new HistoricalHoldingActivationCommand(
            "ACT-014-CONFLICT-KEY",
            materialId,
            departmentId,
            employeeId,
            new BigDecimal("10.0000000"),
            new BigDecimal("50.000000"),
            new BigDecimal("500.0000"),
            "PCS",
            "1C-SRC-014-ORIGINAL",
            LocalDate.of(2026, 10, 1)
        );
        holdingService.activateHistoricalHolding(originalCommand);

        var conflictingCommand = new HistoricalHoldingActivationCommand(
            "ACT-014-CONFLICT-KEY",
            materialId,
            departmentId,
            employeeId,
            new BigDecimal("99.0000000"), // Different quantity!
            new BigDecimal("50.000000"),
            new BigDecimal("4950.0000"),
            "PCS",
            "1C-SRC-014-ORIGINAL",
            LocalDate.of(2026, 10, 1)
        );

        assertThatThrownBy(() -> holdingService.activateHistoricalHolding(conflictingCommand))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Payload differs from existing activation under idempotency key");
    }
}

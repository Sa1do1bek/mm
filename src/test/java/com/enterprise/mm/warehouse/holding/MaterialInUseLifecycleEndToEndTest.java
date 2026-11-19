package com.enterprise.mm.warehouse.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.StockBalanceMutationService;
import com.enterprise.mm.warehouse.holding.HoldingCommands.AcknowledgeHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.ConsumeHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.DispatchHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HistoricalActivationResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HistoricalHoldingActivationCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingConsumptionResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingHandoverResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingReturnResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementQuery;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementReport;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingTransferResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingWriteOffResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.ReturnHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.TransferHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.WriteOffHoldingCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E2E 03 Scenario:
 * End-to-end operational lifecycle for Materials in Use (Account 014):
 * Historical 1C Activation -> Native Dispatch -> Recipient Acknowledgment ->
 * Inter-Holder Transfer -> Warehouse Return -> Operational Consumption -> Write-off ->
 * Statement Reconciliation with zero delta.
 */
class MaterialInUseLifecycleEndToEndTest {

    private static final String TENANT = "tenant-navoiy";
    private static final UUID COMPANY = UUID.randomUUID();

    private WarehouseHoldingRepository holdingRepository;
    private WarehouseHoldingHandoverRepository handoverRepository;
    private WarehouseHoldingTransferRepository transferRepository;
    private StockBalanceRepository stockBalanceRepository;
    private StockMovementRepository stockMovementRepository;
    private StockBalanceMutationService stockBalanceMutationService;
    private IdempotencyService idempotencyService;
    private WarehouseHoldingService holdingService;

    private final Map<UUID, WarehouseHolding> holdingStore = new HashMap<>();
    private final Map<UUID, WarehouseHoldingHandover> handoverStore = new HashMap<>();
    private final Map<UUID, WarehouseHoldingTransfer> transferStore = new HashMap<>();
    private final Map<String, StockBalance> stockBalanceStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        holdingStore.clear();
        handoverStore.clear();
        transferStore.clear();
        stockBalanceStore.clear();

        holdingRepository = mock(WarehouseHoldingRepository.class);
        handoverRepository = mock(WarehouseHoldingHandoverRepository.class);
        transferRepository = mock(WarehouseHoldingTransferRepository.class);
        stockBalanceRepository = mock(StockBalanceRepository.class);
        stockMovementRepository = mock(StockMovementRepository.class);
        stockBalanceMutationService = new StockBalanceMutationService();
        idempotencyService = mock(IdempotencyService.class);

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

        when(holdingRepository.findAll()).thenAnswer(inv -> new ArrayList<>(holdingStore.values()));

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

        when(handoverRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.ofNullable(handoverStore.get(id));
        });

        when(handoverRepository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(inv -> {
                String key = inv.getArgument(2);
                return handoverStore.values().stream()
                    .filter(ho -> key != null && key.equals(ho.getIdempotencyKey()))
                    .findFirst();
            });

        when(transferRepository.save(any(WarehouseHoldingTransfer.class))).thenAnswer(inv -> {
            WarehouseHoldingTransfer t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(UUID.randomUUID());
            }
            t.setTenantId(TENANT);
            t.setCompanyId(COMPANY);
            transferStore.put(t.getId(), t);
            return t;
        });

        when(stockBalanceRepository.save(any(StockBalance.class))).thenAnswer(inv -> {
            StockBalance sb = inv.getArgument(0);
            if (sb.getId() == null) {
                sb.setId(UUID.randomUUID());
            }
            stockBalanceStore.put(sb.getMaterialId() + "-" + sb.getWarehouseId(), sb);
            return sb;
        });

        when(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(inv -> {
                UUID matId = inv.getArgument(1);
                UUID whId = inv.getArgument(2);
                StockBalance sb = stockBalanceStore.get(matId + "-" + whId);
                return sb != null ? List.of(sb) : List.of();
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
            transferRepository,
            idempotencyService,
            stockBalanceRepository,
            stockMovementRepository,
            stockBalanceMutationService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("E2E 03: Complete material-in-use lifecycle executes and reconciles cleanly with zero delta")
    void completeMaterialInUseLifecycleExecutesAndReconcilesCleanly() {
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID deptA = UUID.randomUUID();
        UUID emp1 = UUID.randomUUID();
        UUID deptB = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();

        // 1. Historical 1C Opening Activation (20 units into Dept A / Emp 1)
        HistoricalActivationResult actResult = holdingService.activateHistoricalHolding(new HistoricalHoldingActivationCommand(
            "E2E3-ACT-001", materialId, deptA, emp1,
            new BigDecimal("20.0000000"), new BigDecimal("100.00"), new BigDecimal("2000.00"),
            "PCS", "1C-OPENING-014-NAV", LocalDate.of(2026, 10, 1)
        ));
        assertThat(actResult.activeQuantity()).isEqualByComparingTo("20.0000000");

        // 2. Native Warehouse Dispatch (10 units to Dept A / Emp 1)
        HoldingHandoverResult dispResult = holdingService.dispatchHandover(new DispatchHandoverCommand(
            "E2E3-DISP-001", materialId, deptA, emp1,
            new BigDecimal("10.0000000"), new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "PCS", "ISSUE-DOC-901", "HO-E2E3-01"
        ));
        assertThat(dispResult.pendingQuantity()).isEqualByComparingTo("10.0000000");

        // 3. Recipient Acknowledgement by Emp 1
        HoldingHandoverResult ackResult = holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(
            dispResult.handoverId(), emp1, "Accepted 10 units in full"
        ));
        assertThat(ackResult.activeQuantity()).isEqualByComparingTo("30.0000000"); // 20 activation + 10 native

        // 4. Inter-Holder Transfer (12 units transferred from Dept A / Emp 1 to Dept B / Emp 2)
        HoldingTransferResult trResult = holdingService.transferHolding(new TransferHoldingCommand(
            "E2E3-TR-001", actResult.holdingId(), deptB, emp2,
            new BigDecimal("12.0000000"), "Transfer tools to maintenance squad B", "TR-E2E3-101"
        ));
        assertThat(trResult.sourceRemainingActiveQuantity()).isEqualByComparingTo("18.0000000");
        assertThat(trResult.targetActiveQuantity()).isEqualByComparingTo("12.0000000");

        // 5. Holding Return to Warehouse (Dept A returns 5 surplus units back to warehouse)
        HoldingReturnResult retResult = holdingService.returnHoldingToWarehouse(new ReturnHoldingCommand(
            "E2E3-RET-001", actResult.holdingId(), warehouseId,
            new BigDecimal("5.0000000"), "1010", "GOOD", new BigDecimal("100.00")
        ));
        assertThat(retResult.remainingActiveQuantity()).isEqualByComparingTo("13.0000000");
        assertThat(retResult.returnedQuantity()).isEqualByComparingTo("5.0000000");

        // 6. Operational Consumption (Dept B consumes 4 units during scheduled overhaul)
        HoldingConsumptionResult consResult = holdingService.consumeHolding(new ConsumeHoldingCommand(
            "E2E3-CONS-001", trResult.targetHoldingId(),
            new BigDecimal("4.0000000"), "Installed in overhaul unit 3", LocalDate.of(2026, 10, 14)
        ));
        assertThat(consResult.remainingActiveQuantity()).isEqualByComparingTo("8.0000000");
        assertThat(consResult.financialExpenseRecorded()).isFalse();

        // 7. Write-off (Dept B writes off 2 units due to unrepairable operational damage)
        HoldingWriteOffResult woResult = holdingService.writeOffHolding(new WriteOffHoldingCommand(
            "E2E3-WO-001", trResult.targetHoldingId(),
            new BigDecimal("2.0000000"), "Cracked casing, decommissioned", "ACT-WO-2026-E2E", LocalDate.of(2026, 10, 14)
        ));
        assertThat(woResult.remainingActiveQuantity()).isEqualByComparingTo("6.0000000");
        assertThat(woResult.financialExpenseRecorded()).isFalse();

        // 8. Reconcile complete holding statements
        HoldingStatementReport report = holdingService.generateHoldingStatement(new HoldingStatementQuery(materialId, null, null, "014"));
        assertThat(report).isNotNull();
        assertThat(report.totalHoldings()).isEqualTo(2);
        assertThat(report.balancedHoldings()).isEqualTo(2);
        assertThat(report.discrepancyHoldings()).isEqualTo(0);
        assertThat(report.isFullyBalanced()).isTrue();

        // Active quantities in field: Dept A has 13, Dept B has 6. Total active = 19.
        assertThat(report.totalActiveQuantity()).isEqualByComparingTo("19.0000000");
        assertThat(report.totalReturnedQuantity()).isEqualByComparingTo("5.0000000");
        assertThat(report.totalConsumedQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(report.totalWrittenOffQuantity()).isEqualByComparingTo("2.0000000");
        // Sum: 19 active + 5 returned + 4 consumed + 2 writtenOff = 30 total originally dispatched/activated!
        assertThat(report.totalActiveQuantity()
            .add(report.totalReturnedQuantity())
            .add(report.totalConsumedQuantity())
            .add(report.totalWrittenOffQuantity()))
            .isEqualByComparingTo("30.0000000");

        // Verify warehouse received restored 5 units
        StockBalance whBalance = stockBalanceStore.get(materialId + "-" + warehouseId);
        assertThat(whBalance).isNotNull();
        assertThat(whBalance.getQuantity()).isEqualByComparingTo("5.0000000");
        assertThat(whBalance.getAvailableQuantity()).isEqualByComparingTo("5.0000000");
    }
}

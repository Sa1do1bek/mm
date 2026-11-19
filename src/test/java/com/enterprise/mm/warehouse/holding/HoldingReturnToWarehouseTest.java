package com.enterprise.mm.warehouse.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.StockBalanceMutationService;
import com.enterprise.mm.warehouse.holding.HoldingCommands.AcknowledgeHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.DispatchHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingHandoverResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingReturnResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.ReturnHoldingCommand;
import java.math.BigDecimal;
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
 * P3 AT05 Acceptance Test:
 * Verifies holding return to warehouse restoration and over-return prevention.
 * Returning material reduces active holding quantity, restores on-hand warehouse stock
 * with designated account and valuation, and strictly forbids returning more than active quantity.
 */
class HoldingReturnToWarehouseTest {

    private static final String TENANT = "tenant-navoiy";
    private static final UUID COMPANY = UUID.randomUUID();

    private WarehouseHoldingRepository holdingRepository;
    private WarehouseHoldingHandoverRepository handoverRepository;
    private StockBalanceRepository stockBalanceRepository;
    private StockMovementRepository stockMovementRepository;
    private StockBalanceMutationService stockBalanceMutationService;
    private IdempotencyService idempotencyService;
    private WarehouseHoldingService holdingService;

    private final Map<UUID, WarehouseHolding> holdingStore = new HashMap<>();
    private final Map<UUID, WarehouseHoldingHandover> handoverStore = new HashMap<>();
    private final Map<String, StockBalance> stockBalanceStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        holdingStore.clear();
        handoverStore.clear();
        stockBalanceStore.clear();

        holdingRepository = mock(WarehouseHoldingRepository.class);
        handoverRepository = mock(WarehouseHoldingHandoverRepository.class);
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

        when(stockBalanceRepository.save(any(StockBalance.class))).thenAnswer(inv -> {
            StockBalance sb = inv.getArgument(0);
            if (sb.getId() == null) {
                sb.setId(UUID.randomUUID());
            }
            String key = sb.getMaterialId() + "-" + sb.getWarehouseId();
            stockBalanceStore.put(key, sb);
            return sb;
        });

        when(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            any(), any(), any()
        )).thenAnswer(inv -> {
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
    @DisplayName("P3 AT05.1: Partial holding return restores warehouse stock and reduces active holding")
    void partialHoldingReturnRestoresWarehouseStockAndDecreasesHolding() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        // Dispatch 10 units and acknowledge to make active
        var disp = new DispatchHandoverCommand(
            "DISP-RET-001", materialId, departmentId, employeeId,
            new BigDecimal("10.0000000"), new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "PCS", "DOC-RET-01", "HO-RET-01"
        );
        HoldingHandoverResult dRes = holdingService.dispatchHandover(disp);
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(dRes.handoverId(), employeeId, "ok"));

        // Return 4 units to warehouse
        var returnCmd = new ReturnHoldingCommand(
            "RET-001", dRes.holdingId(), warehouseId,
            new BigDecimal("4.0000000"), "1010", "GOOD", new BigDecimal("100.00")
        );
        HoldingReturnResult result = holdingService.returnHoldingToWarehouse(returnCmd);

        assertThat(result).isNotNull();
        assertThat(result.returnedQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(result.remainingActiveQuantity()).isEqualByComparingTo("6.0000000");
        assertThat(result.restoredStockQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(result.restoredStockValue()).isEqualByComparingTo("400.00");

        // Check holding state
        WarehouseHolding holding = holdingStore.get(dRes.holdingId());
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("6.0000000");
        assertThat(holding.getReturnedQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(holding.getHoldingStatus()).isEqualTo(HoldingStatus.ACTIVE);

        // Check warehouse stock balance
        StockBalance balance = stockBalanceStore.get(materialId + "-" + warehouseId);
        assertThat(balance).isNotNull();
        assertThat(balance.getQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("P3 AT05.2: Over-return exceeding active holding quantity is strictly prevented")
    void overReturnExceedingActiveHoldingQuantityIsStrictlyPrevented() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        var disp = new DispatchHandoverCommand(
            "DISP-RET-002", materialId, departmentId, employeeId,
            new BigDecimal("10.0000000"), new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "PCS", "DOC-RET-02", "HO-RET-02"
        );
        HoldingHandoverResult dRes = holdingService.dispatchHandover(disp);
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(dRes.handoverId(), employeeId, "ok"));

        // Attempt to return 11 units when only 10 are active
        var overReturnCmd = new ReturnHoldingCommand(
            "RET-OVER", dRes.holdingId(), warehouseId,
            new BigDecimal("11.0000000"), "1010", "GOOD", new BigDecimal("100.00")
        );

        assertThatThrownBy(() -> holdingService.returnHoldingToWarehouse(overReturnCmd))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot return quantity exceeding active holding quantity");

        // Verify holding and warehouse balances remain untouched
        WarehouseHolding holding = holdingStore.get(dRes.holdingId());
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("10.0000000");
        assertThat(holding.getReturnedQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(stockBalanceStore.get(materialId + "-" + warehouseId)).isNull();
    }

    @Test
    @DisplayName("P3 AT05.3: Full return transitions holding status to RETURNED")
    void fullReturnTransitionsHoldingStatusToReturned() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        var disp = new DispatchHandoverCommand(
            "DISP-RET-003", materialId, departmentId, employeeId,
            new BigDecimal("5.0000000"), new BigDecimal("100.00"), new BigDecimal("500.00"),
            "PCS", "DOC-RET-03", "HO-RET-03"
        );
        HoldingHandoverResult dRes = holdingService.dispatchHandover(disp);
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(dRes.handoverId(), employeeId, "ok"));

        var returnCmd = new ReturnHoldingCommand(
            "RET-FULL", dRes.holdingId(), warehouseId,
            new BigDecimal("5.0000000"), "1010", "GOOD", new BigDecimal("100.00")
        );
        HoldingReturnResult result = holdingService.returnHoldingToWarehouse(returnCmd);

        assertThat(result.remainingActiveQuantity()).isEqualByComparingTo("0.0000000");
        WarehouseHolding holding = holdingStore.get(dRes.holdingId());
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(holding.getReturnedQuantity()).isEqualByComparingTo("5.0000000");
        assertThat(holding.getHoldingStatus()).isEqualTo(HoldingStatus.RETURNED);

        StockBalance balance = stockBalanceStore.get(materialId + "-" + warehouseId);
        assertThat(balance).isNotNull();
        assertThat(balance.getQuantity()).isEqualByComparingTo("5.0000000");
    }
}

package com.enterprise.mm.warehouse.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.warehouse.holding.HoldingCommands.AcknowledgeHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.ConsumeHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.DispatchHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingConsumptionResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingHandoverResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingWriteOffResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.WriteOffHoldingCommand;
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
 * P3 AT02 Acceptance Test:
 * Verifies materials-in-use (Account 014) consumption and write-off effects.
 * Critical Invariant (D02 & P3 AT02): No second financial expense is recorded
 * upon consumption or write-off because account 014 goods were already expensed
 * at initial issuance.
 */
class HoldingExpenseConsumptionTest {

    private static final String TENANT = "tenant-navoiy";
    private static final UUID COMPANY = UUID.randomUUID();

    private WarehouseHoldingRepository holdingRepository;
    private WarehouseHoldingHandoverRepository handoverRepository;
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

        when(idempotencyService.execute(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            if (inv.getArgument(5) instanceof Supplier<?> s) {
                return s.get();
            }
            return null;
        });

        holdingService = new WarehouseHoldingService(holdingRepository, handoverRepository, idempotencyService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P3 AT02.1: Consumption reduces active holding quantity without secondary financial expense")
    void consumptionReducesActiveHoldingWithoutSecondaryFinancialExpense() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        // Dispatch 10 units and acknowledge to make active
        var disp = new DispatchHandoverCommand(
            "DISP-EXP-001", materialId, departmentId, employeeId,
            new BigDecimal("10.0000000"), new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "PCS", "DOC-001", "HO-001"
        );
        HoldingHandoverResult dRes = holdingService.dispatchHandover(disp);
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(dRes.handoverId(), employeeId, "ok"));

        // Consume 4 units
        var consume = new ConsumeHoldingCommand(
            "CONS-001", dRes.holdingId(), new BigDecimal("4.0000000"),
            "Used during workshop operation", LocalDate.of(2026, 10, 14)
        );
        HoldingConsumptionResult result = holdingService.consumeHolding(consume);

        assertThat(result).isNotNull();
        assertThat(result.consumedQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(result.remainingActiveQuantity()).isEqualByComparingTo("6.0000000");
        // Verify no second expense
        assertThat(result.financialExpenseRecorded()).isFalse();
        assertThat(result.expensePolicy()).isEqualTo("PRIOR_EXPENSED_AT_INITIAL_ISSUE");

        WarehouseHolding holding = holdingStore.get(dRes.holdingId());
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("6.0000000");
        assertThat(holding.getConsumedQuantity()).isEqualByComparingTo("4.0000000");
        assertThat(holding.getHoldingStatus()).isEqualTo(HoldingStatus.ACTIVE);
    }

    @Test
    @DisplayName("P3 AT02.2: Full write-off transitions holding to WRITTEN_OFF status without repeated expense")
    void writeOffExhaustedHoldingTransitionsStatusWithoutRepeatedExpense() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        var disp = new DispatchHandoverCommand(
            "DISP-WO-001", materialId, departmentId, employeeId,
            new BigDecimal("5.0000000"), new BigDecimal("100.00"), new BigDecimal("500.00"),
            "PCS", "DOC-002", "HO-002"
        );
        HoldingHandoverResult dRes = holdingService.dispatchHandover(disp);
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(dRes.handoverId(), employeeId, "ok"));

        // Write off all 5 units due to full operational wear
        var writeOff = new WriteOffHoldingCommand(
            "WO-001", dRes.holdingId(), new BigDecimal("5.0000000"),
            "Operational wear and tear beyond repair", "ACT-WO-2026-99", LocalDate.of(2026, 10, 14)
        );
        HoldingWriteOffResult result = holdingService.writeOffHolding(writeOff);

        assertThat(result.writtenOffQuantity()).isEqualByComparingTo("5.0000000");
        assertThat(result.remainingActiveQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(result.financialExpenseRecorded()).isFalse();
        assertThat(result.actNumber()).isEqualTo("ACT-WO-2026-99");

        WarehouseHolding holding = holdingStore.get(dRes.holdingId());
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(holding.getWrittenOffQuantity()).isEqualByComparingTo("5.0000000");
        assertThat(holding.getHoldingStatus()).isEqualTo(HoldingStatus.WRITTEN_OFF);
    }

    @Test
    @DisplayName("P3 AT02.3: Consuming more than active holding quantity is strictly rejected")
    void consumingMoreThanActiveHoldingQuantityIsRejected() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        var disp = new DispatchHandoverCommand(
            "DISP-REJ-001", materialId, departmentId, employeeId,
            new BigDecimal("3.0000000"), new BigDecimal("100.00"), new BigDecimal("300.00"),
            "PCS", "DOC-003", "HO-003"
        );
        HoldingHandoverResult dRes = holdingService.dispatchHandover(disp);
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(dRes.handoverId(), employeeId, "ok"));

        var overConsume = new ConsumeHoldingCommand(
            "CONS-OVER", dRes.holdingId(), new BigDecimal("5.0000000"),
            "Excessive consumption", LocalDate.of(2026, 10, 14)
        );

        assertThatThrownBy(() -> holdingService.consumeHolding(overConsume))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Holding active quantity is insufficient for consumption");
    }
}

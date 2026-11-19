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
import com.enterprise.mm.warehouse.holding.HoldingCommands.DispatchHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingHandoverResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingTransferResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.TransferHoldingCommand;
import java.math.BigDecimal;
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
 * P3 AT03 Acceptance Test:
 * Verifies inter-holder transfer of materials in use between employees and departments.
 * Ensures total quantity conservation across source and destination holders,
 * rejection of transfers exceeding active holdings, and prevention of self-transfers.
 */
class HoldingTransferConservationTest {

    private static final String TENANT = "tenant-navoiy";
    private static final UUID COMPANY = UUID.randomUUID();

    private WarehouseHoldingRepository holdingRepository;
    private WarehouseHoldingHandoverRepository handoverRepository;
    private WarehouseHoldingTransferRepository transferRepository;
    private IdempotencyService idempotencyService;
    private WarehouseHoldingService holdingService;

    private final Map<UUID, WarehouseHolding> holdingStore = new HashMap<>();
    private final Map<UUID, WarehouseHoldingHandover> handoverStore = new HashMap<>();
    private final Map<UUID, WarehouseHoldingTransfer> transferStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        holdingStore.clear();
        handoverStore.clear();
        transferStore.clear();

        holdingRepository = mock(WarehouseHoldingRepository.class);
        handoverRepository = mock(WarehouseHoldingHandoverRepository.class);
        transferRepository = mock(WarehouseHoldingTransferRepository.class);
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
            idempotencyService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P3 AT03.1: Inter-holder transfer conserves exact total quantity across source and destination")
    void interHolderTransferConservesExactTotalQuantity() {
        UUID materialId = UUID.randomUUID();
        UUID dept1 = UUID.randomUUID();
        UUID emp1 = UUID.randomUUID();
        UUID dept2 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();

        // Dispatch 20 units to Dept1 / Emp1 and acknowledge
        var disp = new DispatchHandoverCommand(
            "DISP-TR-001", materialId, dept1, emp1,
            new BigDecimal("20.0000000"), new BigDecimal("50.00"), new BigDecimal("1000.00"),
            "PCS", "DOC-TR-01", "HO-TR-01"
        );
        HoldingHandoverResult dRes = holdingService.dispatchHandover(disp);
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(dRes.handoverId(), emp1, "accepted"));

        // Transfer 8 units from Dept1 / Emp1 to Dept2 / Emp2
        var transferCmd = new TransferHoldingCommand(
            "TR-001", dRes.holdingId(), dept2, emp2,
            new BigDecimal("8.0000000"), "Transfer to auxiliary workshop", "TR-DOC-101"
        );
        HoldingTransferResult trResult = holdingService.transferHolding(transferCmd);

        assertThat(trResult).isNotNull();
        assertThat(trResult.transferredQuantity()).isEqualByComparingTo("8.0000000");
        assertThat(trResult.sourceRemainingActiveQuantity()).isEqualByComparingTo("12.0000000");
        assertThat(trResult.targetActiveQuantity()).isEqualByComparingTo("8.0000000");
        assertThat(trResult.totalConservedQuantity()).isEqualByComparingTo("20.0000000");

        // Verify source holding
        WarehouseHolding srcHolding = holdingStore.get(dRes.holdingId());
        assertThat(srcHolding.getActiveQuantity()).isEqualByComparingTo("12.0000000");
        assertThat(srcHolding.getTransferredOutQuantity()).isEqualByComparingTo("8.0000000");

        // Verify destination holding
        WarehouseHolding dstHolding = holdingStore.get(trResult.targetHoldingId());
        assertThat(dstHolding).isNotNull();
        assertThat(dstHolding.getDepartmentId()).isEqualTo(dept2);
        assertThat(dstHolding.getEmployeeId()).isEqualTo(emp2);
        assertThat(dstHolding.getActiveQuantity()).isEqualByComparingTo("8.0000000");
        assertThat(dstHolding.getTransferredInQuantity()).isEqualByComparingTo("8.0000000");
    }

    @Test
    @DisplayName("P3 AT03.2: Transfer exceeding source active holding quantity is strictly rejected")
    void transferExceedingSourceActiveHoldingQuantityIsRejected() {
        UUID materialId = UUID.randomUUID();
        UUID dept1 = UUID.randomUUID();
        UUID emp1 = UUID.randomUUID();
        UUID dept2 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();

        var disp = new DispatchHandoverCommand(
            "DISP-TR-002", materialId, dept1, emp1,
            new BigDecimal("5.0000000"), new BigDecimal("50.00"), new BigDecimal("250.00"),
            "PCS", "DOC-TR-02", "HO-TR-02"
        );
        HoldingHandoverResult dRes = holdingService.dispatchHandover(disp);
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(dRes.handoverId(), emp1, "accepted"));

        // Attempt to transfer 6 units when only 5 are active
        var overTransfer = new TransferHoldingCommand(
            "TR-OVER", dRes.holdingId(), dept2, emp2,
            new BigDecimal("6.0000000"), "Over-transfer", "TR-DOC-102"
        );

        assertThatThrownBy(() -> holdingService.transferHolding(overTransfer))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Holding active quantity is insufficient for transfer");

        // Verify source holding unchanged
        WarehouseHolding srcHolding = holdingStore.get(dRes.holdingId());
        assertThat(srcHolding.getActiveQuantity()).isEqualByComparingTo("5.0000000");
        assertThat(srcHolding.getTransferredOutQuantity()).isEqualByComparingTo("0.0000000");
    }

    @Test
    @DisplayName("P3 AT03.3: Self-transfer to the exact same department and employee is rejected")
    void selfTransferToSameHolderIsRejected() {
        UUID materialId = UUID.randomUUID();
        UUID dept1 = UUID.randomUUID();
        UUID emp1 = UUID.randomUUID();

        var disp = new DispatchHandoverCommand(
            "DISP-TR-003", materialId, dept1, emp1,
            new BigDecimal("10.0000000"), new BigDecimal("50.00"), new BigDecimal("500.00"),
            "PCS", "DOC-TR-03", "HO-TR-03"
        );
        HoldingHandoverResult dRes = holdingService.dispatchHandover(disp);
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(dRes.handoverId(), emp1, "accepted"));

        // Self-transfer
        var selfTransfer = new TransferHoldingCommand(
            "TR-SELF", dRes.holdingId(), dept1, emp1,
            new BigDecimal("2.0000000"), "Self-transfer", "TR-DOC-103"
        );

        assertThatThrownBy(() -> holdingService.transferHolding(selfTransfer))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Target holder must differ from source holder");
    }
}

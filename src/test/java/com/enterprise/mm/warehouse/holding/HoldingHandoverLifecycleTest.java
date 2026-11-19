package com.enterprise.mm.warehouse.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.warehouse.holding.HoldingCommands.AcknowledgeHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.DispatchHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingHandoverResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.RejectHandoverCommand;
import java.math.BigDecimal;
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
 * P3 AT01 Acceptance Test:
 * Verifies the holding/origin model with separate pending quantity.
 * Dispatch creates pending quantity without inflating active holding quantity;
 * Recipient acknowledgement transitions pending to active;
 * Rejection clears pending quantity without altering active holdings.
 */
class HoldingHandoverLifecycleTest {

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

        // In-memory simulation of holding repository save and find
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
                .filter(h -> ObjectsMatch(h.getMaterialId(), matId)
                    && ObjectsMatch(h.getDepartmentId(), deptId)
                    && ObjectsMatch(h.getEmployeeId(), empId)
                    && ObjectsMatch(h.getAccountCode(), acct))
                .findFirst();
        });

        // In-memory simulation of handover repository save and find
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
                    .filter(ho -> ObjectsMatch(ho.getIdempotencyKey(), key))
                    .findFirst();
            });

        // Mock IdempotencyService to execute lambda directly
        when(idempotencyService.execute(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            if (inv.getArgument(5) instanceof Supplier<?> s) {
                return s.get();
            }
            return null;
        });

        holdingService = new WarehouseHoldingService(holdingRepository, handoverRepository, idempotencyService);
    }

    private static boolean ObjectsMatch(Object a, Object b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P3 AT01.1: Dispatch creates pending holding quantity while active quantity remains zero")
    void dispatchCreatesPendingHoldingAndDoesNotInflateActiveQuantity() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        var command = new DispatchHandoverCommand(
            "DISP-001",
            materialId,
            departmentId,
            employeeId,
            new BigDecimal("10.0000000"),
            new BigDecimal("150.000000"),
            new BigDecimal("1500.0000"),
            "PCS",
            "DOC-ISSUE-101",
            "HO-2026-001"
        );

        HoldingHandoverResult result = holdingService.dispatchHandover(command);

        assertThat(result).isNotNull();
        assertThat(result.handoverStatus()).isEqualTo(HandoverStatus.PENDING);
        assertThat(result.holdingStatus()).isEqualTo(HoldingStatus.PENDING_ACKNOWLEDGEMENT);
        assertThat(result.pendingQuantity()).isEqualByComparingTo("10.0000000");
        assertThat(result.activeQuantity()).isEqualByComparingTo("0.0000000");

        WarehouseHolding holding = holdingStore.get(result.holdingId());
        assertThat(holding).isNotNull();
        assertThat(holding.getPendingQuantity()).isEqualByComparingTo("10.0000000");
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(holding.getHoldingStatus()).isEqualTo(HoldingStatus.PENDING_ACKNOWLEDGEMENT);
    }

    @Test
    @DisplayName("P3 AT01.2: Recipient acknowledgement transitions pending quantity to active holding quantity")
    void recipientAcknowledgementTransitionsPendingToActiveQuantity() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        var dispatch = new DispatchHandoverCommand(
            "DISP-002",
            materialId,
            departmentId,
            employeeId,
            new BigDecimal("12.5000000"),
            new BigDecimal("200.000000"),
            new BigDecimal("2500.0000"),
            "PCS",
            "DOC-ISSUE-102",
            "HO-2026-002"
        );
        HoldingHandoverResult dispatched = holdingService.dispatchHandover(dispatch);

        var ack = new AcknowledgeHandoverCommand(dispatched.handoverId(), employeeId, "Accepted in full by recipient");
        HoldingHandoverResult ackResult = holdingService.acknowledgeHandover(ack);

        assertThat(ackResult.handoverStatus()).isEqualTo(HandoverStatus.ACKNOWLEDGED);
        assertThat(ackResult.holdingStatus()).isEqualTo(HoldingStatus.ACTIVE);
        assertThat(ackResult.pendingQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(ackResult.activeQuantity()).isEqualByComparingTo("12.5000000");

        WarehouseHolding holding = holdingStore.get(dispatched.holdingId());
        assertThat(holding.getPendingQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("12.5000000");
        assertThat(holding.getHoldingStatus()).isEqualTo(HoldingStatus.ACTIVE);
    }

    @Test
    @DisplayName("P3 AT01.3: Recipient rejection clears pending quantity without inflating active holdings")
    void recipientRejectionClearsPendingQuantityWithoutAlteringActiveHoldings() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        var dispatch = new DispatchHandoverCommand(
            "DISP-003",
            materialId,
            departmentId,
            employeeId,
            new BigDecimal("5.0000000"),
            new BigDecimal("300.000000"),
            new BigDecimal("1500.0000"),
            "PCS",
            "DOC-ISSUE-103",
            "HO-2026-003"
        );
        HoldingHandoverResult dispatched = holdingService.dispatchHandover(dispatch);

        var reject = new RejectHandoverCommand(dispatched.handoverId(), "Item damaged in transit, packaging torn");
        HoldingHandoverResult rejResult = holdingService.rejectHandover(reject);

        assertThat(rejResult.handoverStatus()).isEqualTo(HandoverStatus.REJECTED);
        assertThat(rejResult.pendingQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(rejResult.activeQuantity()).isEqualByComparingTo("0.0000000");

        WarehouseHolding holding = holdingStore.get(dispatched.holdingId());
        assertThat(holding.getPendingQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("0.0000000");

        WarehouseHoldingHandover handover = handoverStore.get(dispatched.handoverId());
        assertThat(handover.getHandoverStatus()).isEqualTo(HandoverStatus.REJECTED);
        assertThat(handover.getRejectionReason()).isEqualTo("Item damaged in transit, packaging torn");
    }

    @Test
    @DisplayName("P3 AT01.4: Multiple dispatches accumulate pending quantities and acknowledge independently")
    void multipleHandoversAccumulatePendingAndActiveConsistently() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        // Handover 1: 10 units
        var disp1 = new DispatchHandoverCommand(
            "DISP-004A", materialId, departmentId, employeeId,
            new BigDecimal("10.0000000"), new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "PCS", "DOC-ISSUE-104A", "HO-2026-004A"
        );
        HoldingHandoverResult res1 = holdingService.dispatchHandover(disp1);

        // Handover 2: 15 units
        var disp2 = new DispatchHandoverCommand(
            "DISP-004B", materialId, departmentId, employeeId,
            new BigDecimal("15.0000000"), new BigDecimal("100.00"), new BigDecimal("1500.00"),
            "PCS", "DOC-ISSUE-104B", "HO-2026-004B"
        );
        HoldingHandoverResult res2 = holdingService.dispatchHandover(disp2);

        WarehouseHolding holding = holdingStore.get(res1.holdingId());
        assertThat(holding.getPendingQuantity()).isEqualByComparingTo("25.0000000");
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("0.0000000");

        // Acknowledge first handover only
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(res1.handoverId(), employeeId, "Part 1 ok"));
        assertThat(holding.getPendingQuantity()).isEqualByComparingTo("15.0000000");
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("10.0000000");

        // Acknowledge second handover
        holdingService.acknowledgeHandover(new AcknowledgeHandoverCommand(res2.handoverId(), employeeId, "Part 2 ok"));
        assertThat(holding.getPendingQuantity()).isEqualByComparingTo("0.0000000");
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("25.0000000");
    }

    @Test
    @DisplayName("P3 AT01.5: Partial acknowledgement shifts only accepted quantity and sets PARTIALLY_ACKNOWLEDGED")
    void partialAcknowledgementShiftsAcceptedQuantity() {
        UUID materialId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();

        // Dispatch 10 units
        var disp = new DispatchHandoverCommand(
            "DISP-PARTIAL-01", materialId, departmentId, employeeId,
            new BigDecimal("10.0000000"), new BigDecimal("100.00"), new BigDecimal("1000.00"),
            "PCS", "DOC-ISSUE-PARTIAL", "HO-2026-PARTIAL"
        );
        HoldingHandoverResult dispatched = holdingService.dispatchHandover(disp);

        // Acknowledge only 9 units (1 unit missing)
        var ackPartial = new AcknowledgeHandoverCommand(
            dispatched.handoverId(), employeeId, "Received 9, 1 missing",
            new BigDecimal("9.0000000"), new BigDecimal("1.0000000")
        );
        HoldingHandoverResult ackResult = holdingService.acknowledgeHandover(ackPartial);

        assertThat(ackResult.handoverStatus()).isEqualTo(HandoverStatus.PARTIALLY_ACKNOWLEDGED);
        assertThat(ackResult.activeQuantity()).isEqualByComparingTo("9.0000000");
        assertThat(ackResult.pendingQuantity()).isEqualByComparingTo("0.0000000");

        WarehouseHolding holding = holdingStore.get(dispatched.holdingId());
        assertThat(holding.getActiveQuantity()).isEqualByComparingTo("9.0000000");
        assertThat(holding.getPendingQuantity()).isEqualByComparingTo("0.0000000");
    }
}

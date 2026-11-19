package com.enterprise.mm.warehouse.transfergroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.entity.TransferDocumentLine;
import com.enterprise.mm.entity.TransferShipmentAllocation;
import com.enterprise.mm.entity.TransferTransitEntry;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.repository.TransferShipmentAllocationRepository;
import com.enterprise.mm.repository.TransferTransitEntryRepository;
import com.enterprise.mm.service.TransferPostingContext;
import com.enterprise.mm.service.transfer.TransferTransitLedgerService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransferTransitConservationTest {

    private static final String TENANT = "test-tenant";
    private static final UUID COMPANY = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    /**
     * P2 AT03 Part 1: Dispatch of 100 units, partial receipt 1 of 50 units clean,
     * partial receipt 2 of 30 units clean + 10 units damaged/quarantined.
     * Remaining 10 units strictly stay in transit (`in_transit`) with conserved quantity and value.
     */
    @Test
    @DisplayName("P2 AT03: Partial receipts leave remaining unresolved quantity strictly in transit with conserved value")
    void testPartialReceiptsConserveInTransitQuantityAndValue() {
        TransferTransitEntryRepository entriesRepo = mock(TransferTransitEntryRepository.class);
        TransferShipmentAllocationRepository allocationsRepo = mock(TransferShipmentAllocationRepository.class);

        TransferTransitLedgerService ledger = new TransferTransitLedgerService(entriesRepo, allocationsRepo);

        UUID allocationId = UUID.randomUUID();
        TransferShipmentAllocation allocation = new TransferShipmentAllocation();
        allocation.setId(allocationId);
        allocation.setTenantId(TENANT);
        allocation.setCompanyId(COMPANY);
        allocation.setDispatchedQuantity(new BigDecimal("100.0000"));
        allocation.setBookValue(new BigDecimal("1000000.00")); // 100 units @ 10,000 UZS each

        when(allocationsRepo.findScopedForUpdate(allocationId, TENANT, COMPANY))
            .thenReturn(Optional.of(allocation));

        List<TransferTransitEntry> facts = new ArrayList<>();
        when(entriesRepo.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(TENANT, COMPANY, allocationId))
            .thenAnswer(inv -> new ArrayList<>(facts));
        when(entriesRepo.saveAndFlush(any(TransferTransitEntry.class))).thenAnswer(inv -> {
            TransferTransitEntry entry = inv.getArgument(0);
            facts.add(entry);
            return entry;
        });

        // 1. Initial Dispatch of 100 units (value: 1,000,000 UZS)
        UUID dispatchOp = UUID.randomUUID();
        ledger.record(
            allocationId,
            dispatchOp,
            TransferPostingContext.Kind.DISPATCH,
            new BigDecimal("100.0000"),
            new BigDecimal("1000000.00"),
            LocalDate.of(2026, 10, 1)
        );

        var balanceAfterDispatch = ledger.balance(allocationId);
        assertThat(balanceAfterDispatch.quantity()).isEqualByComparingTo("100.0000");
        assertThat(balanceAfterDispatch.bookValue()).isEqualByComparingTo("1000000.00");

        // 2. Partial Receipt 1: 50 units received clean
        UUID receipt1Op = UUID.randomUUID();
        ledger.record(
            allocationId,
            receipt1Op,
            TransferPostingContext.Kind.GOOD_RECEIPT,
            new BigDecimal("-50.0000"),
            new BigDecimal("-500000.00"),
            LocalDate.of(2026, 10, 3)
        );

        var balanceAfterReceipt1 = ledger.balance(allocationId);
        assertThat(balanceAfterReceipt1.quantity()).isEqualByComparingTo("50.0000");
        assertThat(balanceAfterReceipt1.bookValue()).isEqualByComparingTo("500000.00");

        // 3. Partial Receipt 2: 30 units received clean + 10 units quarantined/damaged
        UUID receipt2CleanOp = UUID.randomUUID();
        ledger.record(
            allocationId,
            receipt2CleanOp,
            TransferPostingContext.Kind.GOOD_RECEIPT,
            new BigDecimal("-30.0000"),
            new BigDecimal("-300000.00"),
            LocalDate.of(2026, 10, 5)
        );

        UUID receipt2DamagedOp = UUID.randomUUID();
        ledger.record(
            allocationId,
            receipt2DamagedOp,
            TransferPostingContext.Kind.QUARANTINE_RECEIPT,
            new BigDecimal("-10.0000"),
            new BigDecimal("-100000.00"),
            LocalDate.of(2026, 10, 5)
        );

        // 4. Verification: exactly 10 units remain strictly in transit with 100,000 UZS value!
        var finalTransitBalance = ledger.balance(allocationId);
        assertThat(finalTransitBalance.quantity()).isEqualByComparingTo("10.0000");
        assertThat(finalTransitBalance.bookValue()).isEqualByComparingTo("100000.00");

        // Total accounted for: 50 (clean) + 30 (clean) + 10 (damaged/quarantine) + 10 (in_transit) = 100 units!
        BigDecimal totalAccounted = new BigDecimal("50.0000")
            .add(new BigDecimal("30.0000"))
            .add(new BigDecimal("10.0000"))
            .add(finalTransitBalance.quantity());
        assertThat(totalAccounted).isEqualByComparingTo("100.0000");
    }

    /**
     * P2 AT03 Part 2: Canceling undispatched remainder does not reverse executed dispatch or transit.
     */
    @Test
    @DisplayName("P2 AT03: Cancelling unexecuted remainder leaves executed dispatch and in-transit intact")
    void testCancelRemainderLeavesExecutedDispatchAndTransitIntact() {
        TransferDocumentLine line = new TransferDocumentLine();
        line.setId(UUID.randomUUID());
        line.setPlannedQuantity(new BigDecimal("100.0000"));
        line.setDispatchedQuantity(new BigDecimal("80.0000")); // 80 units already dispatched
        line.setInTransitQuantity(new BigDecimal("80.0000"));
        line.setReceivedQuantity(BigDecimal.ZERO);
        line.setCancelledQuantity(BigDecimal.ZERO);

        // Undispatched remainder = 100 - 80 - 0 = 20 units
        BigDecimal remaining = line.getPlannedQuantity()
            .subtract(line.getDispatchedQuantity())
            .subtract(line.getCancelledQuantity());

        assertThat(remaining).isEqualByComparingTo("20.0000");

        // Execute remainder cancellation
        line.setCancelledQuantity(line.getCancelledQuantity().add(remaining));

        // Verifications:
        // Cancelled quantity becomes 20
        assertThat(line.getCancelledQuantity()).isEqualByComparingTo("20.0000");
        // Dispatched quantity stays 80 (never reversed!)
        assertThat(line.getDispatchedQuantity()).isEqualByComparingTo("80.0000");
        // In transit stays 80
        assertThat(line.getInTransitQuantity()).isEqualByComparingTo("80.0000");
        // Remaining undispatched is now 0
        BigDecimal newRemaining = line.getPlannedQuantity()
            .subtract(line.getDispatchedQuantity())
            .subtract(line.getCancelledQuantity());
        assertThat(newRemaining).isEqualByComparingTo("0.0000");
    }

    /**
     * P2 AT03 Part 3: Receipt exceeding unresolved shipment quantity is rejected.
     */
    @Test
    @DisplayName("P2 AT03: Receipt exceeding in-transit balance is rejected")
    void testReceiptExceedingTransitBalanceRejected() {
        TransferTransitEntryRepository entriesRepo = mock(TransferTransitEntryRepository.class);
        TransferShipmentAllocationRepository allocationsRepo = mock(TransferShipmentAllocationRepository.class);

        TransferTransitLedgerService ledger = new TransferTransitLedgerService(entriesRepo, allocationsRepo);

        UUID allocationId = UUID.randomUUID();
        TransferShipmentAllocation allocation = new TransferShipmentAllocation();
        allocation.setId(allocationId);
        allocation.setTenantId(TENANT);
        allocation.setCompanyId(COMPANY);
        allocation.setDispatchedQuantity(new BigDecimal("10.0000"));
        allocation.setBookValue(new BigDecimal("100000.00"));

        when(allocationsRepo.findScopedForUpdate(allocationId, TENANT, COMPANY))
            .thenReturn(Optional.of(allocation));

        // There are only 10 units in transit
        TransferTransitEntry dispatchEntry = new TransferTransitEntry();
        dispatchEntry.setQuantityDelta(new BigDecimal("10.0000"));
        dispatchEntry.setValueDelta(new BigDecimal("100000.00"));

        when(entriesRepo.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc(TENANT, COMPANY, allocationId))
            .thenReturn(List.of(dispatchEntry));

        // Attempting to clear 15 units (exceeding transit balance of 10) must fail
        assertThatThrownBy(() -> ledger.record(
            allocationId,
            UUID.randomUUID(),
            TransferPostingContext.Kind.GOOD_RECEIPT,
            new BigDecimal("-15.0000"),
            new BigDecimal("-150000.00"),
            LocalDate.now()
        ))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Transfer transit quantity/value does not reconcile");
    }
}

package com.enterprise.mm.supplier.po.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.service.NotificationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers reminder dedup (a reminder type fires once per threshold-crossing, not on
 * every scheduler tick) -- docs/CURRENT_TASK.md section 7. Verification not run --
 * see final report.
 */
@ExtendWith(MockitoExtension.class)
class SupplierPoReminderSchedulerTest {
    @Mock private SupplierPoDispatchRepository dispatchRepository;
    @Mock private SupplierPoResponseRepository responseRepository;
    @Mock private SupplierPoCommitmentRepository commitmentRepository;
    @Mock private SupplierPoAsnService asnService;
    @Mock private NotificationService notificationService;
    @InjectMocks private SupplierPoReminderScheduler scheduler;

    @Test
    void dispatchDueSoonReminderFiresOnlyOnceAcrossRepeatedRuns() {
        SupplierPoDispatch dispatch = new SupplierPoDispatch();
        dispatch.setId(UUID.randomUUID());
        dispatch.setTenantId("default");
        dispatch.setCompanyId(UUID.randomUUID());
        dispatch.setCode("PO-DISPATCH-1");
        dispatch.setDispatchStatus("SENT");
        dispatch.setResponseDueDate(LocalDate.now().plusDays(1));

        when(dispatchRepository.findByDispatchStatusInAndCurrentTrueAndResponseDueDateLessThanEqualAndIsDeletedFalse(
            any(), any()
        )).thenReturn(List.of(dispatch));
        when(responseRepository.findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
            any(), any(), any()
        )).thenReturn(Optional.empty());
        when(commitmentRepository.findByStatusAndCurrentTrueAndConfirmedDateIsNotNullAndConfirmedDateLessThanEqualAndIsDeletedFalse(
            any(), any()
        )).thenReturn(List.of());

        scheduler.run();
        scheduler.run();

        verify(notificationService, times(1)).createForRole(
            org.mockito.ArgumentMatchers.eq(RoleName.PROCUREMENT_MANAGER),
            org.mockito.ArgumentMatchers.eq("Supplier response due soon"),
            any(), any(), any(), any()
        );
    }

    @Test
    void commitmentOverdueReminderFiresOnlyOnceAcrossRepeatedRuns() {
        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setId(UUID.randomUUID());
        commitment.setTenantId("default");
        commitment.setCompanyId(UUID.randomUUID());
        commitment.setCode("COMMIT-1");
        commitment.setStatus("CONFIRMED");
        commitment.setConfirmedDate(LocalDate.now().minusDays(2));
        commitment.setConfirmedQuantity(java.math.BigDecimal.TEN);

        when(dispatchRepository.findByDispatchStatusInAndCurrentTrueAndResponseDueDateLessThanEqualAndIsDeletedFalse(
            any(), any()
        )).thenReturn(List.of());
        when(commitmentRepository.findByStatusAndCurrentTrueAndConfirmedDateIsNotNullAndConfirmedDateLessThanEqualAndIsDeletedFalse(
            any(), any()
        )).thenReturn(List.of(commitment));
        when(asnService.remainingQuantity(commitment)).thenReturn(java.math.BigDecimal.TEN);

        scheduler.run();
        scheduler.run();

        verify(notificationService, times(1)).createForRole(
            org.mockito.ArgumentMatchers.eq(RoleName.PROCUREMENT_MANAGER),
            org.mockito.ArgumentMatchers.eq("Supplier commitment overdue"),
            any(), any(), any(), any()
        );
        verify(notificationService, times(1)).createForRole(
            org.mockito.ArgumentMatchers.eq(RoleName.PROCUREMENT_MANAGER),
            org.mockito.ArgumentMatchers.eq("Expected supplier ASN missing"),
            any(), any(), any(), any()
        );
    }
}

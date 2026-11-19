package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.service.NotificationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reminder jobs for: response due-soon, response overdue, commitment due-soon,
 * commitment overdue, expected-ASN-missing. Mirrors
 * {@code ServiceExecutionOverdueScheduler}'s cron-config-property + per-row
 * tenant/company context swap pattern, but adds the V217 dedup timestamp columns
 * that template lacks -- each reminder type fires at most once per
 * threshold-crossing, not on every tick (a simple "already reminded" check, kept
 * proportionate per docs/CURRENT_TASK.md rather than a generic dedup framework).
 */
@Component
@RequiredArgsConstructor
public class SupplierPoReminderScheduler {
    private static final int DUE_SOON_WINDOW_DAYS = 3;
    private static final List<String> ACTIVE_DISPATCH_STATUSES = List.of("SENT");

    private final SupplierPoDispatchRepository dispatchRepository;
    private final SupplierPoResponseRepository responseRepository;
    private final SupplierPoCommitmentRepository commitmentRepository;
    private final SupplierPoAsnService asnService;
    private final NotificationService notificationService;

    @Scheduled(cron = "${mm.po-collaboration-reminder.cron:0 0 8 * * *}")
    public void run() {
        String previousTenant = TenantContext.currentTenant();
        var previousCompany = CompanyContext.currentCompany();
        try {
            remindDispatches();
            remindCommitments();
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }

    private void remindDispatches() {
        LocalDate today = LocalDate.now();
        LocalDate dueSoonHorizon = today.plusDays(DUE_SOON_WINDOW_DAYS);
        for (SupplierPoDispatch dispatch : dispatchRepository
            .findByDispatchStatusInAndCurrentTrueAndResponseDueDateLessThanEqualAndIsDeletedFalse(ACTIVE_DISPATCH_STATUSES, dueSoonHorizon)) {
            withContext(dispatch.getTenantId(), dispatch.getCompanyId(), () -> {
                SupplierPoResponse response = responseRepository
                    .findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
                        dispatch.getTenantId(), dispatch.getCompanyId(), dispatch.getId()
                    ).orElse(null);
                boolean alreadyResponded = response != null && !"DRAFT".equals(response.getResponseStatus());
                if (alreadyResponded) {
                    return;
                }
                boolean overdue = dispatch.getResponseDueDate().isBefore(today);
                if (overdue) {
                    if (dispatch.getOverdueReminderSentAt() == null) {
                        notify("Supplier response overdue", "Purchase order dispatch " + dispatch.getCode()
                            + " response was due " + dispatch.getResponseDueDate() + " and has not been received.",
                            dispatch.getPurchaseOrderId());
                        dispatch.setOverdueReminderSentAt(LocalDateTime.now());
                        dispatchRepository.save(dispatch);
                    }
                } else if (dispatch.getDueSoonReminderSentAt() == null) {
                    notify("Supplier response due soon", "Purchase order dispatch " + dispatch.getCode()
                        + " response is due " + dispatch.getResponseDueDate() + ".",
                        dispatch.getPurchaseOrderId());
                    dispatch.setDueSoonReminderSentAt(LocalDateTime.now());
                    dispatchRepository.save(dispatch);
                }
            });
        }
    }

    private void remindCommitments() {
        LocalDate today = LocalDate.now();
        LocalDate dueSoonHorizon = today.plusDays(DUE_SOON_WINDOW_DAYS);
        for (SupplierPoCommitment commitment : commitmentRepository
            .findByStatusAndCurrentTrueAndConfirmedDateIsNotNullAndConfirmedDateLessThanEqualAndIsDeletedFalse("CONFIRMED", dueSoonHorizon)) {
            withContext(commitment.getTenantId(), commitment.getCompanyId(), () -> {
                boolean overdue = commitment.getConfirmedDate().isBefore(today);
                if (overdue) {
                    if (commitment.getOverdueReminderSentAt() == null) {
                        notify("Supplier commitment overdue", "Confirmed delivery commitment " + commitment.getCode()
                            + " was due " + commitment.getConfirmedDate() + " and is not yet fulfilled.",
                            commitment.getPurchaseOrderId());
                        commitment.setOverdueReminderSentAt(LocalDateTime.now());
                        commitmentRepository.save(commitment);
                    }
                    BigDecimal remaining = asnService.remainingQuantity(commitment);
                    BigDecimal confirmed = commitment.getConfirmedQuantity() == null ? BigDecimal.ZERO : commitment.getConfirmedQuantity();
                    if (remaining.compareTo(confirmed) >= 0 && commitment.getAsnMissingReminderSentAt() == null) {
                        notify("Expected supplier ASN missing", "No shipment has been reported yet for confirmed commitment "
                            + commitment.getCode() + " (due " + commitment.getConfirmedDate() + ").",
                            commitment.getPurchaseOrderId());
                        commitment.setAsnMissingReminderSentAt(LocalDateTime.now());
                        commitmentRepository.save(commitment);
                    }
                } else if (commitment.getDueSoonReminderSentAt() == null) {
                    notify("Supplier commitment due soon", "Confirmed delivery commitment " + commitment.getCode()
                        + " is due " + commitment.getConfirmedDate() + ".",
                        commitment.getPurchaseOrderId());
                    commitment.setDueSoonReminderSentAt(LocalDateTime.now());
                    commitmentRepository.save(commitment);
                }
            });
        }
    }

    private void notify(String title, String message, java.util.UUID purchaseOrderId) {
        notificationService.createForRole(
            RoleName.PROCUREMENT_MANAGER, title, message, title,
            NotificationEntityType.PURCHASE_ORDER.name(), purchaseOrderId
        );
    }

    private void withContext(String tenantId, java.util.UUID companyId, Runnable action) {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        action.run();
    }
}

package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.repository.SupplierMagicLinkRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supersedes the current dispatch/response/unfulfilled-commitments and revokes
 * PO-scoped magic links when a PO's commercial content changes after dispatch.
 *
 * <p><b>Hook status as of Phase A:</b> {@code PurchaseOrderService.update(...)} is
 * currently only reachable while the PO is DRAFT or RETURNED (see the guard at the
 * top of {@code update()}); there is presently no code path that returns a SENT PO
 * to DRAFT/RETURNED for editing, so no "PO revision after SENT" flow exists yet in
 * this codebase. Per the task's instruction to add the minimal hook without
 * redesigning PO revision/approval flow, {@link #supersedeForPoChange} is wired into
 * {@code PurchaseOrderService.update()} unconditionally -- it is a cheap no-op when
 * no dispatch has ever been created for the PO (the common case today, since
 * update() cannot currently run against a dispatched PO). The moment any future
 * workflow allows editing a previously-dispatched PO, supersession fires
 * automatically without further changes here.
 *
 * <p>PO cancellation (which IS reachable today via
 * {@code PurchaseOrderService.cancel()}) is handled separately by
 * {@link SupplierPoCancellationService}, which reuses this class for the
 * dispatch/response/commitment/magic-link teardown but records a CANCELLED (not
 * SUPERSEDED) dispatch status.
 */
@Service
@RequiredArgsConstructor
public class SupplierPoSupersessionService {
    private static final List<String> UNFULFILLED_COMMITMENT_STATUSES = List.of("PROPOSED", "CONFIRMED");

    private final SupplierPoDispatchRepository dispatchRepository;
    private final SupplierPoResponseRepository responseRepository;
    private final SupplierPoCommitmentRepository commitmentRepository;
    private final SupplierMagicLinkRepository magicLinkRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public boolean supersedeForPoChange(UUID purchaseOrderId, String reason) {
        return deactivateCurrentDispatch(purchaseOrderId, reason, "SUPERSEDED");
    }

    /** Used by {@link SupplierPoCancellationService}; records dispatch_status=CANCELLED
     * instead of SUPERSEDED. */
    @Transactional
    boolean deactivateForCancellation(UUID purchaseOrderId, String reason) {
        return deactivateCurrentDispatch(purchaseOrderId, reason, "CANCELLED");
    }

    private boolean deactivateCurrentDispatch(UUID purchaseOrderId, String reason, String terminalDispatchStatus) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        var currentDispatch = dispatchRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(tenantId, companyId, purchaseOrderId);
        if (currentDispatch.isEmpty()) {
            return false;
        }
        SupplierPoDispatch dispatch = currentDispatch.get();
        if ("SUPERSEDED".equals(dispatch.getDispatchStatus()) || "CANCELLED".equals(dispatch.getDispatchStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        dispatch.setDispatchStatus(terminalDispatchStatus);
        if ("CANCELLED".equals(terminalDispatchStatus)) {
            dispatch.setCancelledAt(now);
        } else {
            dispatch.setSupersededAt(now);
        }
        dispatch.setCurrent(false);
        dispatchRepository.save(dispatch);

        responseRepository
            .findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(tenantId, companyId, dispatch.getId())
            .ifPresent(response -> {
                response.setResponseStatus("SUPERSEDED");
                response.setCurrent(false);
                responseRepository.save(response);
            });

        commitmentRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                tenantId, companyId, purchaseOrderId, UNFULFILLED_COMMITMENT_STATUSES
            )
            .forEach(commitment -> {
                commitment.setStatus("SUPERSEDED");
                commitment.setCurrent(false);
                commitmentRepository.save(commitment);
            });

        for (var link : magicLinkRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusAndIsDeletedFalse(
            tenantId, companyId, purchaseOrderId, "ISSUED"
        )) {
            link.setStatus("REVOKED");
            link.setRevokedAt(now);
            magicLinkRepository.save(link);
        }

        auditService.log("PurchaseOrder", purchaseOrderId, "PO_SUPPLIER_COLLABORATION_SUPERSEDED", reason, null, null);
        if ("SUPERSEDED".equals(terminalDispatchStatus)) {
            notificationService.createForRole(
                RoleName.PROCUREMENT_MANAGER,
                "Supplier collaboration superseded",
                "Purchase order revision superseded the supplier dispatch/response/commitments: " + reason,
                "PO collaboration superseded " + purchaseOrderId,
                NotificationEntityType.PURCHASE_ORDER.name(),
                purchaseOrderId
            );
        }
        return true;
    }
}

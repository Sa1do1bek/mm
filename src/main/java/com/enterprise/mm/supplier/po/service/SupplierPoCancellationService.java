package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.error.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Called from {@code PurchaseOrderService.cancel()}. Cancels the active dispatch (if
 * any), supersedes unfulfilled commitments, revokes PO-scoped magic links, and
 * delegates draft-ASN cleanup to {@link SupplierPoAsnCancellationHook}.
 *
 * <p>Phase B judgment call: submitted-but-unresolved portal ASNs (SENT/CONFIRMED,
 * i.e. not yet received) against this PO's commitments block the cancellation outright
 * with a clear domain error, rather than being silently cancelled -- see
 * {@link SupplierPoAsnCancellationHookImpl}'s javadoc for the reasoning.
 */
@Service
@RequiredArgsConstructor
public class SupplierPoCancellationService {
    private final SupplierPoSupersessionService supersessionService;
    private final SupplierPoAsnCancellationHook asnCancellationHook;
    private final SupplierPoAsnService asnService;

    @Transactional
    public void cancel(UUID purchaseOrderId) {
        if (asnService.hasUnresolvedSubmittedAsns(purchaseOrderId)) {
            throw new BusinessException(
                "Purchase order has submitted supplier ASN(s) not yet received; resolve or receive them before cancelling"
            );
        }
        supersessionService.deactivateForCancellation(purchaseOrderId, "Purchase order cancelled");
        asnCancellationHook.cancelDraftAsns(purchaseOrderId);
    }
}

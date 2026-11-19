package com.enterprise.mm.supplier.po.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Real {@link SupplierPoAsnCancellationHook} implementation, now that the
 * ASN-from-commitment service exists (Phase B). {@code @Primary} makes this bean win
 * over {@link NoopSupplierPoAsnCancellationHook} wherever {@code SupplierPoAsnCancellationHook}
 * is injected as a single bean by type (that is how {@code SupplierPoCancellationService}
 * consumes it -- a plain field injection, not a {@code List<...>} or a qualifier), so
 * this implementation is actually invoked instead of shadowed by the no-op.
 *
 * <p>Judgment call (see docs/CURRENT_TASK.md section 1): only DRAFT (never-submitted)
 * portal ASNs are cancelled here. Already-submitted ASNs (SENT/CONFIRMED) against the
 * cancelled PO's commitments are deliberately left alone -- silently cancelling a
 * shipment already in transit/confirmed is unsafe. Instead,
 * {@link SupplierPoCancellationService} is expected to consult
 * {@link SupplierPoAsnService#hasUnresolvedSubmittedAsns(UUID)} (wired directly, not
 * through this hook, since "block cancellation" is a different concern than "clean up
 * drafts") and refuse the cancellation with a clear domain error when such ASNs exist.
 */
@Component
@Primary
@RequiredArgsConstructor
public class SupplierPoAsnCancellationHookImpl implements SupplierPoAsnCancellationHook {
    private static final Logger log = LoggerFactory.getLogger(SupplierPoAsnCancellationHookImpl.class);
    private final SupplierPoAsnService asnService;

    @Override
    public void cancelDraftAsns(UUID purchaseOrderId) {
        int cancelled = asnService.cancelDraftAsns(purchaseOrderId);
        log.info("Cancelled {} draft supplier-portal ASN(s) for purchase order {}", cancelled, purchaseOrderId);
    }
}

package com.enterprise.mm.supplier.po.service;

import java.util.UUID;

/**
 * Extension point for Phase B: when a purchase order with an active supplier
 * collaboration is cancelled, any supplier-portal-originated draft ASNs
 * ({@code advance_shipping_notices.source = 'SUPPLIER_PORTAL'}, not yet sent/received)
 * against that PO should also be cancelled. ASN creation/mutation is entirely out of
 * this phase's charter (see docs/CURRENT_TASK.md scope boundaries), so
 * {@link SupplierPoCancellationService} calls this interface rather than touching
 * ASN entities directly. The default bean ({@link NoopSupplierPoAsnCancellationHook})
 * is a documented no-op; Phase B should provide its own {@code @Primary} (or replace
 * the no-op) implementation once the ASN-from-commitment service exists.
 */
public interface SupplierPoAsnCancellationHook {
    void cancelDraftAsns(UUID purchaseOrderId);
}

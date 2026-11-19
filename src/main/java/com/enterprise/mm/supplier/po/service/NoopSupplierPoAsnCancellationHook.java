package com.enterprise.mm.supplier.po.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default no-op {@link SupplierPoAsnCancellationHook}. See that interface's Javadoc --
 * Phase B (ASN-from-commitment) should supply the real implementation. */
@Component
public class NoopSupplierPoAsnCancellationHook implements SupplierPoAsnCancellationHook {
    private static final Logger log = LoggerFactory.getLogger(NoopSupplierPoAsnCancellationHook.class);

    @Override
    public void cancelDraftAsns(UUID purchaseOrderId) {
        log.debug(
            "No-op ASN cancellation hook invoked for purchase order {}; ASN-from-commitment is not implemented in this phase",
            purchaseOrderId
        );
    }
}

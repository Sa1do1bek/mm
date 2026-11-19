package com.enterprise.mm.erp;

/**
 * Arrival policy for one document version. The same version never adds another posting.
 * Disabled exchange skips the document. Confirmation of an already posted document does not post again.
 */
public final class ErpExchangeProcessor {
    public Decision accept(boolean enabled, String mode, boolean envelopeExists, int existingPostings) {
        if (!enabled) {
            return Decision.skip();
        }
        if (envelopeExists) {
            return new Decision(false, false, existingPostings > 0, false);
        }
        boolean postNow = ErpDocumentKinds.POST_WHEN_CONFIRMED.equals(mode);
        return new Decision(false, true, postNow, postNow);
    }

    public Decision confirm(int existingPostings) {
        if (existingPostings > 0) {
            return new Decision(false, false, true, false);
        }
        return new Decision(false, false, true, true);
    }

    public record Decision(boolean skipped, boolean created, boolean posted, boolean addPosting) {
        static Decision skip() {
            return new Decision(true, false, false, false);
        }
    }
}

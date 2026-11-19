package com.enterprise.mm.enums;

public enum SupplierMagicLinkRequestReason {
    MATCHED_QUEUED,
    COMPANY_NOT_FOUND,
    COMPANY_AMBIGUOUS,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_NOT_ELIGIBLE,
    NO_ACTIVE_INVITATION,
    NO_DELIVERY_FOUND,
    NO_ACTIVE_PORTAL_DOCUMENT,
    RATE_LIMITED,
    /** Reserved for a future PO-collaboration self-service magic-link request flow
     * (Phase B territory) -- no active, non-superseded PO dispatch was found for the
     * requesting supplier account. */
    NO_ACTIVE_PO_DISPATCH
}

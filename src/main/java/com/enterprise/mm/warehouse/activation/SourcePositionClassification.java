package com.enterprise.mm.warehouse.activation;

/**
 * Six distinct classification states for 1C source account positions.
 * Ensures strict segregation between active operational deltas, value-only lines,
 * historical references, and unresolved mapping issues.
 */
public enum SourcePositionClassification {
    /** Already covered by native MM opening or subsequent operational postings. */
    ALREADY_REPRESENTED,

    /** Eligible new operational delta approved to be posted into native stock. */
    ELIGIBLE_DELTA,

    /** Closed or historical records preserved strictly for audit evidence. */
    HISTORICAL_ONLY,

    /** Accounting value without physical quantity (account balance without units). */
    VALUE_ONLY,

    /** Negative or disputed positions. Retains original sign in reports without physical availability. */
    NEGATIVE_DISPUTED,

    /** Missing valid material, warehouse, or account binding. Blocked from execution. */
    UNRESOLVED
}

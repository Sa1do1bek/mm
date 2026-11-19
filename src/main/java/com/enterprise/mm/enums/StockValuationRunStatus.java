package com.enterprise.mm.enums;

/** Run lifecycle codes; persisted as names in the inherited String status field. */
public enum StockValuationRunStatus {
    DRAFT,
    QUEUED,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED
}

package com.enterprise.mm.service;

/**
 * Keeps report counts on the exact supplier-governance queue predicate used by
 * the corresponding drilldown.
 */
@FunctionalInterface
public interface ReportSupplierQueueCounter {
    long blacklistUnblockCountForCurrentCompany();
}

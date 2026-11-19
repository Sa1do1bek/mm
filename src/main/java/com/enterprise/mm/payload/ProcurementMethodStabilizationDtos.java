package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ProcurementMethodStabilizationDtos {
    private ProcurementMethodStabilizationDtos() {}

    public record ClassificationReport(
        List<ClassificationRow> rows
    ) {}

    public record ClassificationRow(
        String recordType,
        String issueCode,
        String classification,
        long count
    ) {}

    public record MethodUsageReport(
        List<CapabilityUsageRow> capabilities,
        List<MethodUsageRow> usage
    ) {}

    public record CapabilityUsageRow(
        String methodCode,
        String state,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String currencyCode,
        String reason,
        Integer configurationVersion
    ) {}

    public record MethodUsageRow(
        String methodCode,
        long methodDecisionCount,
        long procedureCount,
        long awardOutcomeCount,
        long directAwardCount,
        long frameworkCount,
        long frameworkLedgerEntryCount
    ) {}
}

package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;

public final class BudgetHistoricalReconciliationDtos {
    private BudgetHistoricalReconciliationDtos() {
    }

    public enum Classification {
        MATCHED,
        MISSING_LEDGER,
        AGGREGATE_MISMATCH,
        AMBIGUOUS_HISTORY,
        REQUIRES_MANUAL_REVIEW
    }

    public record Finding(
        String documentType,
        UUID documentId,
        String documentStatus,
        Classification classification,
        String reason
    ) {
    }

    public record Report(
        String tenantId,
        UUID companyId,
        int matched,
        int missingLedger,
        int aggregateMismatch,
        int ambiguousHistory,
        int requiresManualReview,
        List<Finding> findings
    ) {
    }
}

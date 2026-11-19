package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;

public final class SegregationOfDutyDtos {
    private SegregationOfDutyDtos() {
    }

    public record SegregationOfDutyCheckRequest(
        String entityType,
        UUID entityId,
        String actionCode,
        String currentStatus,
        String createdBy,
        String updatedBy,
        String ownerUsername,
        String requestedBy
    ) {
    }

    public record SegregationOfDutyCheckResult(
        boolean allowed,
        String decision,
        List<SegregationOfDutyRuleResult> rules
    ) {
    }

    public record SegregationOfDutyRuleResult(
        UUID ruleId,
        String ruleType,
        String severity,
        String decision,
        String matchedPrincipal,
        String message
    ) {
    }
}
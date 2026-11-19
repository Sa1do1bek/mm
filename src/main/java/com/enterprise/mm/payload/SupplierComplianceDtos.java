package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class SupplierComplianceDtos {
    private SupplierComplianceDtos() {
    }

    public record SupplierPortalActionRequest(String comment, String inviteToken) {
    }

    public record SupplierPortalLifecycleCommand(
        @NotNull Long expectedVersion,
        @Size(max = 2000) String comment
    ) {
    }

    public record SupplierPortalReviewCommand(
        @NotNull Long expectedVersion,
        @Size(max = 1000) String comment
    ) {
    }

    public record SupplierKycDecisionRequest(String riskLevel, String findings, Boolean remediationRequired) {
    }

    public record SupplierCapaActionRequest(String comment) {
    }

    public record VersionedSupplierComplianceCommand(
        @NotNull Long expectedVersion,
        @Size(max = 2000) String comment
    ) {
    }

    public record SupplierKycDecisionCommand(
        @NotNull Long expectedVersion,
        String riskLevel,
        @Size(max = 4000) String findings,
        Boolean remediationRequired
    ) {
    }

    public record SupplierCapaActionCommand(
        @NotNull Long expectedVersion,
        @Size(max = 4000) String comment
    ) {
    }

    public record SupplierComplianceSummary(
        UUID supplierId,
        long activePortalAccounts,
        long failedKycChecks,
        long highRiskRelatedParties,
        long openCorrectiveActions,
        String recommendedStatus
    ) {
    }
}

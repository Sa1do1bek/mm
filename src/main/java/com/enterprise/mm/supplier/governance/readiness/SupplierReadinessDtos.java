package com.enterprise.mm.supplier.governance.readiness;

import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.OverallStatus;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class SupplierReadinessDtos {
    public record ReadinessBlocker(
        String code,
        StageKey stage,
        UUID applicationId,
        String requirementCode,
        String message,
        String correctiveAction,
        String ownerRole,
        LocalDate dueDate
    ) {
    }

    public record ReadinessAction(
        String code,
        boolean enabled,
        String disabledReason,
        String ownerRole,
        UUID applicationId,
        Long expectedVersion
    ) {
    }

    public record ReadinessStage(
        StageKey key,
        StageState state,
        int completedItems,
        int totalItems,
        List<ReadinessBlocker> blockers,
         boolean locked
    ) {
        public ReadinessStage {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }

        public ReadinessStage(
            StageKey key,
            StageState state,
            int completedItems,
            int totalItems,
            List<ReadinessBlocker> blockers
        ) {
            this(key, state, completedItems, totalItems, blockers, false);
        }
    }

    public record ScopeReadiness(
        UUID applicationId,
        String accreditationType,
        String requestedScope,
        String applicationStatus,
        int evidenceCompleted,
        int evidenceTotal,
        StageState reviewState,
        String decision,
        LocalDate validTo,
        boolean effective,
        List<ReadinessBlocker> blockers
    ) {
        public ScopeReadiness {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    public record SupplierReadinessSummary(
        OverallStatus overallStatus,
        int readinessPercent,
        boolean activationEligible,
        List<ReadinessStage> stages,
        List<ScopeReadiness> scopes,
        List<ReadinessBlocker> blockers,
        ReadinessAction nextAction,
        LocalDateTime calculatedAt,
        String policyVersion
    ) {
        public SupplierReadinessSummary {
            stages = stages == null ? List.of() : List.copyOf(stages);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    private SupplierReadinessDtos() {
    }
}

package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SupplierEligibilityDtos {
    private SupplierEligibilityDtos() {
    }

    public record MaterialGroupEligibility(
        UUID materialGroupId,
        boolean eligible,
        String reasonCode,
        String reason,
        UUID applicationId,
        String applicationStatus,
        UUID decisionId,
        LocalDate validTo,
        String conditions
    ) {
    }

    /**
     * Dated/scoped eligibility snapshot shared by the supplier list, dossier, PO, and receipt
     * surfaces (LIVE UX unification): every consumer must derive its status display from this
     * one shape instead of recomputing eligibility independently.
     */
    public record EffectiveSupplierEligibility(
        UUID supplierId,
        LocalDate asOfDate,
        String overallStatus,
        boolean eligibleForRequestedScope,
        List<MaterialGroupEligibility> groups,
        List<BlockingFactor> blockers,
        String summaryReason
    ) {
    }

    /** One identifiable reason a supplier is not (fully) eligible: which scope, which document
     * (if any), and why. */
    public record BlockingFactor(
        String code,
        UUID scopeId,
        String scopeCode,
        String documentType,
        UUID documentId,
        String documentCode,
        String reason,
        LocalDate validTo
    ) {
    }

    /**
     * Complete accreditation demand for one business transaction. Material, service, and work
     * scopes are independent; mixed documents must satisfy every requested scope.
     */
    public record AccreditationDemand(
        Set<UUID> materialGroupIds,
        boolean materialRequired,
        boolean materialScopeComplete,
        boolean serviceRequired,
        boolean worksRequired
    ) {
        public AccreditationDemand {
            materialGroupIds = materialGroupIds == null
                ? Set.of()
                : materialGroupIds.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        }

        public static AccreditationDemand materials(Collection<UUID> materialGroupIds) {
            Set<UUID> groups = materialGroupIds == null
                ? Set.of()
                : materialGroupIds.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
            return new AccreditationDemand(groups, true, true, false, false);
        }

        public static AccreditationDemand services() {
            return new AccreditationDemand(Set.of(), false, true, true, false);
        }

        public static AccreditationDemand works() {
            return new AccreditationDemand(Set.of(), false, true, false, true);
        }
    }

    public record SupplierEligibilityReason(
        String code,
        String message,
        UUID scopeId,
        String scopeCode,
        String documentType,
        UUID documentId,
        String documentCode,
        LocalDate validTo
    ) {
        public SupplierEligibilityReason(String code, String message) {
            this(code, message, null, null, null, null, null, null);
        }
    }

    public record UnifiedSupplierEligibility(
        UUID supplierId,
        LocalDate asOfDate,
        String operation,
        String status,
        boolean newBusinessAllowed,
        boolean existingObligationsAllowed,
        boolean hardBlocked,
        List<SupplierEligibilityReason> reasons,
        List<SupplierEligibilityReason> warnings
    ) {
        public UnifiedSupplierEligibility {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public String summaryReason() {
            if (!reasons.isEmpty()) {
                return reasons.get(0).message();
            }
            if (!warnings.isEmpty()) {
                return warnings.get(0).message();
            }
            return null;
        }
    }
}

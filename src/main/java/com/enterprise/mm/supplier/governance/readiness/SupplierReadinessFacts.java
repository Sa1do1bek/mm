package com.enterprise.mm.supplier.governance.readiness;

import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessBlocker;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SupplierReadinessFacts(
    boolean profileComplete,
    boolean portalAccountReady,
    boolean complianceComplete,
    boolean complianceBlocked,
    boolean complianceLimited,
    boolean manualBlock,
    boolean lifecycleActive,
    boolean activationExists,
    boolean previouslyEffective,
    List<ScopeFact> scopes
) {
    public SupplierReadinessFacts {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public record ScopeFact(
        UUID applicationId,
        String accreditationType,
        String requestedScope,
        String applicationStatus,
        boolean complianceCheckCompleted,
        int mandatoryEvidenceCompleted,
        int mandatoryEvidenceTotal,
        boolean reviewComplete,
        String decision,
        LocalDate validTo,
        List<ReadinessBlocker> blockers
    ) {
        public ScopeFact {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }

        public boolean approvedOn(LocalDate today) {
            return "APPROVED".equals(decision)
                && (validTo == null || !validTo.isBefore(today));
        }

        public boolean conditionalOn(LocalDate today) {
            return "CONDITIONALLY_APPROVED".equals(decision)
                && (validTo == null || !validTo.isBefore(today));
        }
    }
}

package com.enterprise.mm.supplier.governance.readiness;

public final class SupplierReadinessEnums {
    public enum OverallStatus {
        NOT_READY, READY_TO_ACTIVATE, ACTIVE, LIMITED, BLOCKED, EXPIRED
    }

    public enum StageKey {
        MASTER_PROFILE, PORTAL_ACCOUNT, COMPLIANCE_KYC, APPLICATION, EVIDENCE, REVIEW, DECISION, ACTIVATION
    }

    public enum StageState {
        NOT_STARTED, IN_PROGRESS, BLOCKED, COMPLETED, EXPIRED
    }

    public enum EvidenceChecklistItemState {
        MISSING, UPLOADED, UNDER_VERIFICATION, VERIFIED, REJECTED, EXPIRED, NOT_APPLICABLE
    }

    private SupplierReadinessEnums() {
    }
}

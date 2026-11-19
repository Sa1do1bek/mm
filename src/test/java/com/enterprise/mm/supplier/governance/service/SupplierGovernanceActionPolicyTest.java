package com.enterprise.mm.supplier.governance.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessBlocker;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessStage;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplierGovernanceActionPolicyTest {
    private final SupplierGovernanceActionPolicy policy = new SupplierGovernanceActionPolicy();

    @Test
    void reviewReportsPortalWhenEvidenceIsOnlyLockedByEarlierStages() {
        SupplierReadinessSummary readiness = summary(List.of(
            stage(StageKey.PORTAL_ACCOUNT, StageState.IN_PROGRESS, false, List.of()),
            stage(StageKey.APPLICATION, StageState.NOT_STARTED, true, List.of()),
            stage(StageKey.EVIDENCE, StageState.NOT_STARTED, true, List.of())
        ));

        assertThatThrownBy(() -> policy.requireStagePrerequisitesSatisfied(StageKey.REVIEW, readiness))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Complete PORTAL_ACCOUNT first");
    }

    @Test
    void reviewNamesTheMissingEvidenceRequirementWhenEvidenceIsOpen() {
        ReadinessBlocker missing = new ReadinessBlocker(
            "EVIDENCE_MISSING", StageKey.EVIDENCE, null, "TECHNICAL_CAPABILITY",
            "Evidence is missing", "Upload evidence", "SUPPLIER_MANAGER", null);
        SupplierReadinessSummary readiness = summary(List.of(
            stage(StageKey.EVIDENCE, StageState.IN_PROGRESS, false, List.of(missing))
        ));

        assertThatThrownBy(() -> policy.requireStagePrerequisitesSatisfied(StageKey.REVIEW, readiness))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Requirements: TECHNICAL_CAPABILITY");
    }

    private SupplierReadinessSummary summary(List<ReadinessStage> stages) {
        return new SupplierReadinessSummary(null, 0, false, stages, List.of(), List.of(), null, null, "test");
    }

    private ReadinessStage stage(StageKey key, StageState state, boolean locked, List<ReadinessBlocker> blockers) {
        return new ReadinessStage(key, state, 0, 1, blockers, locked);
    }
}

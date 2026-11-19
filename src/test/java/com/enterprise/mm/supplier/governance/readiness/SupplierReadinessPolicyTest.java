package com.enterprise.mm.supplier.governance.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessBlocker;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.OverallStatus;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessFacts.ScopeFact;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SupplierReadinessPolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 23);
    private final SupplierReadinessPolicy policy = new SupplierReadinessPolicy();

    @ParameterizedTest
    @MethodSource("overallStates")
    void calculatesOverallState(SupplierReadinessFacts facts, OverallStatus expected) {
        assertThat(policy.evaluate(facts, TODAY).overallStatus()).isEqualTo(expected);
    }

    static Stream<Arguments> overallStates() {
        return Stream.of(
            Arguments.of(facts(false, true, true, false, false, false, false, false, approved()), OverallStatus.NOT_READY),
            Arguments.of(facts(true, false, true, false, false, false, false, false, approved()), OverallStatus.NOT_READY),
            Arguments.of(facts(true, true, true, true, false, false, false, false, approved()), OverallStatus.BLOCKED),
            Arguments.of(facts(true, true, true, false, false, false, false, false, approved()), OverallStatus.READY_TO_ACTIVATE),
            Arguments.of(facts(true, true, true, false, false, false, true, false, approved()), OverallStatus.ACTIVE),
            Arguments.of(facts(true, true, true, false, true, false, true, false, approved()), OverallStatus.ACTIVE),
            Arguments.of(facts(true, true, true, false, false, false, true, true, expired()), OverallStatus.EXPIRED)
        );
    }

    @Test
    void conditionalScopeCannotCompleteFinalActivation() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, false, false, conditional()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.NOT_READY);
        assertThat(summary.activationEligible()).isFalse();
        assertStage(summary, StageKey.DECISION, StageState.COMPLETED, 1);
    }

    @Test
    void percentageCannotOverrideMissingApprovedScope() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, false, false, conditional()), TODAY);

        assertThat(summary.readinessPercent()).isPositive();
        assertThat(summary.activationEligible()).isFalse();
    }

    @Test
    void readySupplierReceivesActivationAsThePrioritizedNextAction() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, false, false, approved()), TODAY);

        assertThat(summary.nextAction().code()).isEqualTo("ACTIVATE_SUPPLIER");
        assertThat(summary.nextAction().enabled()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("accreditationWorkflowNextActions")
    void incompleteComplianceFollowsAccreditationWorkflowBeforeRunningComplianceCheck(
        String applicationStatus,
        String expectedAction
    ) {
        var summary = policy.evaluate(
            facts(true, true, false, false, false, false, false, false,
                accreditationScope(applicationStatus)), TODAY);

        assertThat(summary.nextAction().code()).isEqualTo(expectedAction);
        assertThat(summary.activationEligible()).isFalse();
    }

    static Stream<Arguments> accreditationWorkflowNextActions() {
        return Stream.of(
            Arguments.of("DRAFT", "SUBMIT"),
            Arguments.of("SUBMITTED", "START_REVIEW"),
            Arguments.of("PENDING_REVIEW", "START_REVIEW"),
            Arguments.of("UNDER_REVIEW", "RUN_COMPLIANCE_CHECK")
        );
    }

    @Test
    void completedComplianceCheckRequiresSubmitForApprovalBeforeReviewCompletion() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, false, false,
                accreditationScope("COMPLIANCE_CHECK", true)), TODAY);

        assertThat(summary.nextAction().code()).isEqualTo("SUBMIT_FOR_APPROVAL");
        assertThat(summary.activationEligible()).isFalse();
    }

    @Test
    void missingPortalAccountBlocksActivation() {
        var summary = policy.evaluate(
            facts(true, false, true, false, false, false, false, false, approved()), TODAY);

        assertThat(summary.stages())
            .filteredOn(stage -> stage.key() == SupplierReadinessEnums.StageKey.PORTAL_ACCOUNT)
            .singleElement()
            .satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(SupplierReadinessEnums.StageState.IN_PROGRESS);
                assertThat(stage.completedItems()).isZero();
                assertThat(stage.blockers()).extracting(ReadinessBlocker::code).contains("PORTAL_ACCOUNT_REQUIRED");
            });
        assertThat(summary.nextAction().code()).isEqualTo("CONFIGURE_PORTAL_ACCOUNT");
        assertThat(summary.nextAction().enabled()).isTrue();
        assertThat(summary.activationEligible()).isFalse();
    }

    @Test
    void expiredAccreditationInvalidatesReadiness() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, true, true, expired()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.EXPIRED);
        assertThat(summary.activationEligible()).isFalse();
        assertThat(summary.stages())
            .filteredOn(stage -> stage.key() == SupplierReadinessEnums.StageKey.ACTIVATION)
            .singleElement()
            .satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(SupplierReadinessEnums.StageState.EXPIRED);
                assertThat(stage.completedItems()).isZero();
            });
        assertThat(summary.nextAction().code()).isEqualTo("RENEW_EVIDENCE");
    }

    @Test
    void complianceBlockInvalidatesCurrentActivationEvenWhenAccreditationIsStillValid() {
        var summary = policy.evaluate(
            facts(true, true, true, true, false, false, true, true, approved()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.BLOCKED);
        assertThat(summary.stages())
            .filteredOn(stage -> stage.key() == StageKey.ACTIVATION)
            .singleElement()
            .satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(StageState.BLOCKED);
                assertThat(stage.completedItems()).isZero();
            });
        assertThat(summary.readinessPercent()).isLessThan(100);
    }

    @Test
    void expiredMandatoryEvidenceInvalidatesReadiness() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, true, true, approvedWithExpiredEvidence()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.EXPIRED);
        assertThat(summary.stages())
            .filteredOn(stage -> stage.key() == StageKey.EVIDENCE)
            .singleElement()
            .satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(StageState.EXPIRED);
                assertThat(stage.completedItems()).isEqualTo(5);
                assertThat(stage.blockers()).extracting(ReadinessBlocker::code).contains("EVIDENCE_EXPIRED");
            });
        assertThat(summary.stages())
            .filteredOn(stage -> stage.key() == StageKey.ACTIVATION)
            .singleElement()
            .satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(StageState.EXPIRED);
                assertThat(stage.completedItems()).isZero();
            });
    }

    @Test
    void allCurrentRequirementsValidButNotActivatedStopsAtActivation() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, false, false, approved()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.READY_TO_ACTIVATE);
        assertStage(summary, StageKey.MASTER_PROFILE, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.PORTAL_ACCOUNT, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.COMPLIANCE_KYC, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.APPLICATION, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.EVIDENCE, StageState.COMPLETED, 6);
        assertStage(summary, StageKey.REVIEW, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.DECISION, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.ACTIVATION, StageState.IN_PROGRESS, 0);
    }

    @Test
    void allCurrentRequirementsValidAndActivatedCompletesTheLifecycle() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, true, true, approved()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.ACTIVE);
        summary.stages().forEach(stage -> assertThat(stage.state()).isEqualTo(StageState.COMPLETED));
        assertThat(summary.readinessPercent()).isEqualTo(100);
    }

    @Test
    void profileRegressionInvalidatesOnlyCurrentActivationSatisfaction() {
        var summary = policy.evaluate(
            facts(false, true, true, false, false, false, true, true, approved()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.NOT_READY);
        assertStage(summary, StageKey.MASTER_PROFILE, StageState.IN_PROGRESS, 0);
        assertStage(summary, StageKey.DECISION, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.ACTIVATION, StageState.NOT_STARTED, 0);
    }

    @Test
    void portalRegressionInvalidatesCurrentReadiness() {
        var summary = policy.evaluate(
            facts(true, false, true, false, false, false, true, true, approved()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.NOT_READY);
        assertStage(summary, StageKey.PORTAL_ACCOUNT, StageState.IN_PROGRESS, 0);
        assertStage(summary, StageKey.DECISION, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.ACTIVATION, StageState.NOT_STARTED, 0);
    }

    @Test
    void currentApprovedScopeDrivesAccreditationStageWhenExpiredHistoryExists() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, false, true, List.of(expired(), approved())), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.READY_TO_ACTIVATE);
        assertStage(summary, StageKey.DECISION, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.ACTIVATION, StageState.IN_PROGRESS, 0);
    }

    @Test
    void incompleteComplianceInvalidatesCurrentReadiness() {
        var summary = policy.evaluate(
            facts(true, true, false, false, false, false, true, true, approved()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.NOT_READY);
        assertStage(summary, StageKey.COMPLIANCE_KYC, StageState.IN_PROGRESS, 0);
        assertStage(summary, StageKey.DECISION, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.ACTIVATION, StageState.NOT_STARTED, 0);
    }

    @Test
    void submittedApplicationWithVerifiedEvidenceCanEnterReviewBeforeCompliance() {
        var summary = policy.evaluate(
            facts(true, true, false, false, false, false, false, false,
                accreditationScope("SUBMITTED")), TODAY);

        assertStage(summary, StageKey.COMPLIANCE_KYC, StageState.IN_PROGRESS, 0);
        assertStage(summary, StageKey.APPLICATION, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.EVIDENCE, StageState.COMPLETED, 6);
        assertStage(summary, StageKey.REVIEW, StageState.IN_PROGRESS, 0);
        assertThat(summary.nextAction().code()).isEqualTo("START_REVIEW");
    }

    @Test
    void rejectedMandatoryEvidenceInvalidatesReadiness() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, true, true, approvedWithRejectedEvidence()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.EXPIRED);
        assertStage(summary, StageKey.EVIDENCE, StageState.BLOCKED, 5);
        assertStage(summary, StageKey.ACTIVATION, StageState.EXPIRED, 0);
        assertThat(summary.blockers()).extracting(ReadinessBlocker::code)
            .doesNotContain("APPROVED_SCOPE_REQUIRED");
    }

    @Test
    void pendingApplicationDoesNotCompleteDecisionOrReadiness() {
        var summary = policy.evaluate(
            facts(true, true, true, false, false, false, false, false, pending()), TODAY);

        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.NOT_READY);
        assertStage(summary, StageKey.APPLICATION, StageState.COMPLETED, 1);
        assertStage(summary, StageKey.EVIDENCE, StageState.COMPLETED, 6);
        assertStage(summary, StageKey.REVIEW, StageState.IN_PROGRESS, 0);
        assertStage(summary, StageKey.DECISION, StageState.IN_PROGRESS, 0);
        assertStage(summary, StageKey.ACTIVATION, StageState.NOT_STARTED, 0);
    }

    @Test
    void profileAloneCannotSkipOnboardingStages() {
        var summary = policy.evaluate(facts(true, false, false, false, false, false, false, false, List.of()), TODAY);
        assertThat(summary.activationEligible()).isFalse();
        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.NOT_READY);
        assertThat(summary.nextAction().code()).isEqualTo("CONFIGURE_PORTAL_ACCOUNT");
        assertStage(summary, StageKey.ACTIVATION, StageState.NOT_STARTED, 0);
        assertThat(summary.blockers()).extracting(ReadinessBlocker::code)
            .contains("PORTAL_ACCOUNT_REQUIRED", "COMPLIANCE_REQUIRED", "APPROVED_SCOPE_REQUIRED");
    }

    @Test
    void activeStatusWithoutActivationRecordStillRequiresExplicitActivation() {
        var facts = new SupplierReadinessFacts(true, true, true, false, false, false,
            true, false, false, List.of(approved()));
        var summary = policy.evaluate(facts, TODAY);
        assertThat(summary.activationEligible()).isTrue();
        assertThat(summary.overallStatus()).isEqualTo(OverallStatus.READY_TO_ACTIVATE);
        assertStage(summary, StageKey.ACTIVATION, StageState.IN_PROGRESS, 0);
    }

    private static SupplierReadinessFacts facts(
        boolean profile,
        boolean portalAccount,
        boolean compliance,
        boolean blocked,
        boolean limited,
        boolean manual,
        boolean activated,
        boolean previous,
        ScopeFact scope
    ) {
        return facts(profile, portalAccount, compliance, blocked, limited, manual, activated, previous, List.of(scope));
    }

    private static SupplierReadinessFacts facts(
        boolean profile,
        boolean portalAccount,
        boolean compliance,
        boolean blocked,
        boolean limited,
        boolean manual,
        boolean activated,
        boolean previous,
        List<ScopeFact> scopes
    ) {
        return new SupplierReadinessFacts(
            profile, portalAccount, compliance, blocked, limited, manual, activated, activated, previous, scopes);
    }

    private static ScopeFact approved() {
        return scope("APPROVED", TODAY.plusYears(1));
    }

    private static ScopeFact conditional() {
        return scope("CONDITIONALLY_APPROVED", TODAY.plusYears(1));
    }

    private static ScopeFact expired() {
        return scope("APPROVED", TODAY.minusDays(1));
    }

    private static ScopeFact approvedWithExpiredEvidence() {
        return scopeWithEvidenceBlocker("EVIDENCE_EXPIRED");
    }

    private static ScopeFact approvedWithRejectedEvidence() {
        return scopeWithEvidenceBlocker("EVIDENCE_REJECTED");
    }

    private static ScopeFact scopeWithEvidenceBlocker(String blockerCode) {
        return new ScopeFact(
            UUID.randomUUID(),
            "GOODS",
            "Electrical materials",
            "APPROVED",
            true,
            5,
            6,
            true,
            "APPROVED",
            TODAY.plusYears(1),
            List.of(new ReadinessBlocker(
                blockerCode,
                StageKey.EVIDENCE,
                null,
                "QUALITY_CERTIFICATE",
                "Evidence requirement QUALITY_CERTIFICATE is " + blockerCode,
                "Provide and verify current evidence",
                "SUPPLIER_MANAGER",
                TODAY.minusDays(1)
            ))
        );
    }

    private static ScopeFact pending() {
        return new ScopeFact(
            UUID.randomUUID(),
            "GOODS",
            "Electrical materials",
            "UNDER_REVIEW",
            true,
            6,
            6,
            false,
            null,
            TODAY.plusYears(1),
            List.of()
        );
    }

    private static ScopeFact accreditationScope(String applicationStatus) {
        return accreditationScope(applicationStatus, false);
    }

    private static ScopeFact accreditationScope(String applicationStatus, boolean complianceCheckCompleted) {
        return new ScopeFact(
            UUID.randomUUID(),
            "GOODS",
            "Electrical materials",
            applicationStatus,
            complianceCheckCompleted,
            6,
            6,
            false,
            null,
            TODAY.plusYears(1),
            List.of()
        );
    }

    private static ScopeFact scope(String decision, LocalDate validTo) {
        return new ScopeFact(
            UUID.randomUUID(),
            "GOODS",
            "Electrical materials",
            decision,
            true,
            6,
            6,
            true,
            decision,
            validTo,
            List.of()
        );
    }

    private static void assertStage(
        SupplierReadinessDtos.SupplierReadinessSummary summary,
        StageKey key,
        StageState state,
        int completedItems
    ) {
        assertThat(summary.stages())
            .filteredOn(stage -> stage.key() == key)
            .singleElement()
            .satisfies(stage -> {
                assertThat(stage.state()).isEqualTo(state);
                assertThat(stage.completedItems()).isEqualTo(completedItems);
            });
    }
}

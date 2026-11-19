package com.enterprise.mm.supplier.governance.readiness;

import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessAction;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessBlocker;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessStage;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ScopeReadiness;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.OverallStatus;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessFacts.ScopeFact;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SupplierReadinessPolicy {
    public static final String POLICY_VERSION = "supplier-readiness-v2";

    public SupplierReadinessSummary evaluate(SupplierReadinessFacts facts, LocalDate today) {
        LocalDate asOfDate = today == null ? LocalDate.now() : today;
        ScopeFact viable = mostAdvanced(facts.scopes(), asOfDate);
        boolean approvedPath = facts.scopes().stream()
            .anyMatch(scope -> effective(scope, asOfDate));
        boolean currentApprovedDecision = facts.scopes().stream()
            .anyMatch(scope -> scope.approvedOn(asOfDate));
        List<ReadinessBlocker> blockers = blockers(facts, currentApprovedDecision);
        List<ReadinessStage> stages = stages(facts, viable, approvedPath, blockers, asOfDate);
        OverallStatus overall = overallStatus(facts, approvedPath);
        boolean activationEligible = activationPrerequisitesSatisfied(facts, approvedPath)
            && (!facts.activationExists() || !facts.lifecycleActive());
        return new SupplierReadinessSummary(
            overall,
            percent(stages),
            activationEligible,
            stages,
            facts.scopes().stream().map(scope -> scope(scope, asOfDate)).toList(),
            blockers,
            nextAction(facts, viable, approvedPath, blockers),
            LocalDateTime.now(),
            POLICY_VERSION
        );
    }

    private OverallStatus overallStatus(SupplierReadinessFacts facts, boolean approvedPath) {
        if (facts.complianceBlocked() || facts.manualBlock()) {
            return OverallStatus.BLOCKED;
        }
        if (!facts.profileComplete() || !facts.portalAccountReady() || !facts.complianceComplete()) {
            return OverallStatus.NOT_READY;
        }
        if (!activationPrerequisitesSatisfied(facts, approvedPath)) {
            return facts.previouslyEffective() && !approvedPath ? OverallStatus.EXPIRED : OverallStatus.NOT_READY;
        }
        return facts.activationExists() && facts.lifecycleActive()
            ? OverallStatus.ACTIVE : OverallStatus.READY_TO_ACTIVATE;
    }

    private boolean effective(ScopeFact scope, LocalDate today) {
        return scope.approvedOn(today)
            && scope.complianceCheckCompleted()
            && scope.mandatoryEvidenceCompleted() >= scope.mandatoryEvidenceTotal()
            && scope.reviewComplete()
            && scope.blockers().isEmpty();
    }

    private List<ReadinessBlocker> blockers(SupplierReadinessFacts facts, boolean currentApprovedDecision) {
        List<ReadinessBlocker> result = new ArrayList<>();
        if (facts.manualBlock()) {
            result.add(blocker("SUPPLIER_MANUALLY_BLOCKED", StageKey.COMPLIANCE_KYC,
                "Supplier is manually blocked", "Resolve the supplier block", "SUPPLIER_MANAGER"));
        }
        if (facts.complianceBlocked()) {
            result.add(blocker("COMPLIANCE_BLOCKED", StageKey.COMPLIANCE_KYC,
                "Compliance checks block supplier readiness", "Resolve compliance blockers", "COMPLIANCE_MANAGER"));
        }
        if (!facts.profileComplete()) {
            result.add(blocker("MASTER_PROFILE_INCOMPLETE", StageKey.MASTER_PROFILE,
                "Supplier master profile is incomplete", "Complete required supplier fields", "SUPPLIER_MANAGER"));
        }
        if (!facts.portalAccountReady()) {
            result.add(blocker("PORTAL_ACCOUNT_REQUIRED", StageKey.PORTAL_ACCOUNT,
                "Supplier has no active portal account", "Create or reactivate a supplier portal account", "SUPPLIER_MANAGER"));
        }
        if (!facts.complianceComplete()) {
            result.add(blocker("COMPLIANCE_REQUIRED", StageKey.COMPLIANCE_KYC,
                "Compliance checks are incomplete", "Run compliance checks", "COMPLIANCE_MANAGER"));
        }
        if (!currentApprovedDecision) {
            result.add(blocker("APPROVED_SCOPE_REQUIRED", StageKey.DECISION,
                "At least one current fully approved scope is required",
                "Complete review and record an unrestricted approval", "SUPPLIER_MANAGER"));
        }
        facts.scopes().forEach(scope -> result.addAll(scope.blockers()));
        return List.copyOf(result);
    }

    private List<ReadinessStage> stages(
        SupplierReadinessFacts facts,
        ScopeFact scope,
        boolean approvedPath,
        List<ReadinessBlocker> blockers,
        LocalDate today
    ) {
        List<ReadinessStage> result = new ArrayList<>();

        // ── STAGE 1: MASTER_PROFILE — always the entry point, never locked ────────
        ReadinessStage profileStage = stage(
            StageKey.MASTER_PROFILE,
            facts.profileComplete() ? StageState.COMPLETED : StageState.IN_PROGRESS,
            facts.profileComplete() ? 1 : 0, 1, blockers, false);
        result.add(profileStage);
        boolean profileDone = profileStage.state() == StageState.COMPLETED;

        // ── STAGE 2: PORTAL_ACCOUNT — locked until Profile is complete ────────────
        ReadinessStage portalStage;
        if (!profileDone) {
            portalStage = lockedStage(StageKey.PORTAL_ACCOUNT, StageKey.MASTER_PROFILE);
        } else {
            portalStage = stage(
                StageKey.PORTAL_ACCOUNT,
                facts.portalAccountReady() ? StageState.COMPLETED : StageState.IN_PROGRESS,
                facts.portalAccountReady() ? 1 : 0, 1, blockers, false);
        }
        result.add(portalStage);
        boolean portalDone = portalStage.state() == StageState.COMPLETED;

        // ── STAGE 3: COMPLIANCE_KYC — locked until Portal is complete ─────────────
        ReadinessStage complianceStage;
        if (!portalDone) {
            complianceStage = lockedStage(StageKey.COMPLIANCE_KYC, StageKey.PORTAL_ACCOUNT);
        } else {
            StageState complianceState = facts.complianceBlocked() || facts.manualBlock()
                ? StageState.BLOCKED
                : facts.complianceComplete() ? StageState.COMPLETED : StageState.IN_PROGRESS;
            complianceStage = stage(
                StageKey.COMPLIANCE_KYC, complianceState,
                facts.complianceComplete() ? 1 : 0, 1, blockers, false);
        }
        result.add(complianceStage);

        // ── STAGE 4: APPLICATION — available after portal setup ──────────────────
        ReadinessStage applicationStage;
        if (!portalDone) {
            applicationStage = lockedStage(StageKey.APPLICATION, StageKey.PORTAL_ACCOUNT);
        } else {
            applicationStage = stage(
                StageKey.APPLICATION,
                scope == null ? StageState.NOT_STARTED : StageState.COMPLETED,
                scope == null ? 0 : 1, 1, blockers, false);
        }
        result.add(applicationStage);
        boolean applicationDone = applicationStage.state() == StageState.COMPLETED;

        // ── STAGE 5: EVIDENCE — locked until Application is complete ──────────────
        int evidenceCompleted = scope == null ? 0
            : scope.mandatoryEvidenceTotal() == 0 ? 1 : scope.mandatoryEvidenceCompleted();
        int evidenceTotal = scope == null ? 1 : Math.max(1, scope.mandatoryEvidenceTotal());
        ReadinessStage evidenceStage;
        if (!applicationDone) {
            evidenceStage = lockedStage(StageKey.EVIDENCE, StageKey.APPLICATION);
        } else {
            evidenceStage = stage(
                StageKey.EVIDENCE, evidenceState(scope, evidenceCompleted, evidenceTotal),
                evidenceCompleted, evidenceTotal, blockers, false);
        }
        result.add(evidenceStage);
        boolean evidenceDone = evidenceStage.state() == StageState.COMPLETED;

        // ── STAGE 6: REVIEW — locked until Evidence is complete ───────────────────
        ReadinessStage reviewStage;
        if (!evidenceDone) {
            reviewStage = lockedStage(StageKey.REVIEW, StageKey.EVIDENCE);
        } else {
            StageState reviewState = scope == null ? StageState.NOT_STARTED
                : scope.reviewComplete() ? StageState.COMPLETED : StageState.IN_PROGRESS;
            reviewStage = stage(
                StageKey.REVIEW, reviewState,
                scope != null && scope.reviewComplete() ? 1 : 0, 1, blockers, false);
        }
        result.add(reviewStage);
        boolean reviewDone = reviewStage.state() == StageState.COMPLETED;

        // ── STAGE 7: DECISION — locked until Review is complete ───────────────────
        boolean currentDecision = scope != null
            && (scope.approvedOn(today) || scope.conditionalOn(today));
        boolean expiredDecision = scope != null
            && scope.validTo() != null && scope.validTo().isBefore(today);
        ReadinessStage decisionStage;
        if (!reviewDone) {
            decisionStage = lockedStage(StageKey.DECISION, StageKey.REVIEW);
        } else {
            StageState decisionState = expiredDecision ? StageState.EXPIRED
                : currentDecision ? StageState.COMPLETED
                : scope == null ? StageState.NOT_STARTED : StageState.IN_PROGRESS;
            decisionStage = stage(
                StageKey.DECISION, decisionState,
                currentDecision ? 1 : 0, 1, blockers, false);
        }
        result.add(decisionStage);
        boolean decisionDone = decisionStage.state() == StageState.COMPLETED
            || decisionStage.state() == StageState.EXPIRED; // expired still means a decision existed

        // ── STAGE 8: ACTIVATION — locked until a Decision exists ─────────────────
        ReadinessStage activationStage;
        if (!decisionDone) {
            activationStage = lockedStage(StageKey.ACTIVATION, StageKey.DECISION);
        } else {
            activationStage = stage(
                StageKey.ACTIVATION, activationState(facts, approvedPath),
                activationCompletedItems(facts, approvedPath), 1, blockers, false);
        }
        result.add(activationStage);

        return List.copyOf(result);
    }

    /**
     * Creates a stage that is fully locked because its prerequisite has not been completed.
     * The stage gets a dedicated LOCKED_BY_PREREQUISITE blocker so the UI and API consumers
     * understand exactly why the stage cannot be acted upon.
     */
    private ReadinessStage lockedStage(StageKey key, StageKey prerequisite) {
        ReadinessBlocker blocker = lockedBlocker(key, prerequisite);
        return new ReadinessStage(key, StageState.NOT_STARTED, 0, 1, List.of(blocker), true);
    }

    private ReadinessBlocker lockedBlocker(StageKey key, StageKey prerequisite) {
        return new ReadinessBlocker(
            "LOCKED_BY_PREREQUISITE",
            key,
            null,
            prerequisite.name(),
            "Stage " + key.name() + " is locked. Complete " + prerequisite.name() + " first.",
            "Complete the " + prerequisite.name().toLowerCase().replace('_', ' ') + " stage before proceeding.",
            "SUPPLIER_MANAGER",
            null
        );
    }

    private StageState activationState(SupplierReadinessFacts facts, boolean approvedPath) {
        if (facts.complianceBlocked() || facts.manualBlock()) {
            return StageState.BLOCKED;
        }
        if (activationCurrentlySatisfied(facts, approvedPath)) {
            return StageState.COMPLETED;
        }
        if (!facts.profileComplete() || !facts.portalAccountReady() || !facts.complianceComplete()) {
            return StageState.NOT_STARTED;
        }
        if (facts.previouslyEffective() && !approvedPath) {
            return StageState.EXPIRED;
        }
        return activationPrerequisitesSatisfied(facts, approvedPath) ? StageState.IN_PROGRESS : StageState.NOT_STARTED;
    }

    private StageState evidenceState(ScopeFact scope, int completed, int total) {
        if (scope == null) {
            return StageState.NOT_STARTED;
        }
        if (hasBlocker(scope, StageKey.EVIDENCE, "EVIDENCE_EXPIRED")) {
            return StageState.EXPIRED;
        }
        if (hasBlocker(scope, StageKey.EVIDENCE, "EVIDENCE_REJECTED")) {
            return StageState.BLOCKED;
        }
        return completed >= total ? StageState.COMPLETED : StageState.IN_PROGRESS;
    }

    private boolean hasBlocker(ScopeFact scope, StageKey stage, String code) {
        return scope.blockers().stream()
            .anyMatch(blocker -> blocker.stage() == stage && code.equals(blocker.code()));
    }

    private int activationCompletedItems(SupplierReadinessFacts facts, boolean approvedPath) {
        return activationCurrentlySatisfied(facts, approvedPath) ? 1 : 0;
    }

    private boolean activationCurrentlySatisfied(SupplierReadinessFacts facts, boolean approvedPath) {
        return facts.activationExists()
            && facts.lifecycleActive()
            && activationPrerequisitesSatisfied(facts, approvedPath);
    }

    private boolean activationPrerequisitesSatisfied(SupplierReadinessFacts facts, boolean approvedPath) {
        return facts.profileComplete()
            && facts.portalAccountReady()
            && facts.complianceComplete()
            && approvedPath
            && !facts.complianceBlocked()
            && !facts.manualBlock();
    }

    private ReadinessStage stage(
        StageKey key,
        StageState state,
        int completed,
        int total,
        List<ReadinessBlocker> blockers,
        boolean locked
    ) {
        return new ReadinessStage(
            key, state, completed, total,
            blockers.stream().filter(b -> b.stage() == key).toList(),
            locked);
    }

    private ScopeReadiness scope(ScopeFact scope, LocalDate today) {
        StageState reviewState = scope.reviewComplete()
            ? StageState.COMPLETED
            : StageState.IN_PROGRESS;
        if (scope.validTo() != null && scope.validTo().isBefore(today)) {
            reviewState = StageState.EXPIRED;
        }
        return new ScopeReadiness(
            scope.applicationId(),
            scope.accreditationType(),
            scope.requestedScope(),
            scope.applicationStatus(),
            scope.mandatoryEvidenceCompleted(),
            scope.mandatoryEvidenceTotal(),
            reviewState,
            scope.decision(),
            scope.validTo(),
            effective(scope, today),
            scope.blockers()
        );
    }

    private ScopeFact mostAdvanced(List<ScopeFact> scopes, LocalDate today) {
        return scopes.stream()
            .max(Comparator
                .comparing((ScopeFact scope) -> effective(scope, today))
                .thenComparing(scope -> scope.approvedOn(today))
                .thenComparing(scope -> scope.conditionalOn(today))
                .thenComparingInt(ScopeFact::mandatoryEvidenceCompleted)
                .thenComparing(ScopeFact::reviewComplete)
                .thenComparing(scope -> scope.validTo() == null ? LocalDate.MAX : scope.validTo()))
            .orElse(null);
    }

    private ReadinessAction nextAction(
        SupplierReadinessFacts facts,
        ScopeFact scope,
        boolean approvedPath,
        List<ReadinessBlocker> blockers
    ) {
        if (facts.complianceBlocked() || facts.manualBlock()) {
            return action("REMEDIATE_BLOCKER", "SUPPLIER_MANAGER", scope);
        }
        if (!facts.profileComplete()) {
            return action("COMPLETE_PROFILE", "SUPPLIER_MANAGER", scope);
        }
        if (!facts.portalAccountReady()) {
            return action("CONFIGURE_PORTAL_ACCOUNT", "SUPPLIER_MANAGER", scope);
        }

        if (scope == null || scope.mandatoryEvidenceCompleted() < scope.mandatoryEvidenceTotal()) {
            String code = blockers.stream().anyMatch(blocker ->
                blocker.code().contains("VERIFICATION") || blocker.code().contains("REJECTED"))
                ? "VERIFY_EVIDENCE" : "UPLOAD_EVIDENCE";
            return action(code, "SUPPLIER_MANAGER", scope);
        }
        if ("NEEDS_CLARIFICATION".equals(scope.applicationStatus())) {
            return action("RESOLVE_CLARIFICATION", "SUPPLIER_MANAGER", scope);
        }
        if ("PENDING_APPROVAL".equals(scope.applicationStatus())) {
            return new ReadinessAction("WAIT_FOR_APPROVAL", false, "Approval is pending",
                "SUPPLIER_MANAGER", scope.applicationId(), null);
        }
        ReadinessAction accreditationWorkflowAction = accreditationWorkflowAction(scope);
        if (accreditationWorkflowAction != null) {
            return accreditationWorkflowAction;
        }
        if (!facts.complianceComplete()) {
            return action("RUN_COMPLIANCE_CHECK", "COMPLIANCE_MANAGER", scope);
        }
        if (!scope.reviewComplete()) {
            return action("START_REVIEW", "SUPPLIER_MANAGER", scope);
        }
        if (facts.previouslyEffective() && !approvedPath) {
            return action("RENEW_EVIDENCE", "SUPPLIER_MANAGER", scope);
        }
        if (!approvedPath) {
            return action("RECORD_DECISION", "SUPPLIER_MANAGER", scope);
        }
        if (!facts.activationExists() || !facts.lifecycleActive()) {
            return action("ACTIVATE_SUPPLIER", "SUPPLIER_MANAGER", scope);
        }
        return new ReadinessAction("NONE", false, "No readiness action required", null, null, null);
    }

    private ReadinessAction accreditationWorkflowAction(ScopeFact scope) {
        return switch (scope.applicationStatus()) {
            case "DRAFT" -> action("SUBMIT", "SUPPLIER_MANAGER", scope);
            case "SUBMITTED", "PENDING_REVIEW" -> action("START_REVIEW", "SUPPLIER_MANAGER", scope);
            case "COMPLIANCE_CHECK", "COMMITTEE_REVIEW" -> action("SUBMIT_FOR_APPROVAL", "SUPPLIER_MANAGER", scope);
            default -> null;
        };
    }

    private ReadinessAction action(String code, String ownerRole, ScopeFact scope) {
        return new ReadinessAction(
            code, true, null, ownerRole,
            scope == null ? null : scope.applicationId(), null);
    }

    private ReadinessBlocker blocker(
        String code,
        StageKey stage,
        String message,
        String correctiveAction,
        String ownerRole
    ) {
        return new ReadinessBlocker(
            code, stage, null, null, message, correctiveAction, ownerRole, null);
    }

    private int percent(List<ReadinessStage> stages) {
        int completed = stages.stream().mapToInt(ReadinessStage::completedItems).sum();
        int total = stages.stream().mapToInt(ReadinessStage::totalItems).sum();
        return total == 0 ? 0 : Math.min(100, Math.round((completed * 100f) / total));
    }
}

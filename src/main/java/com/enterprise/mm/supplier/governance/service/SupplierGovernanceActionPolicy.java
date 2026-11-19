package com.enterprise.mm.supplier.governance.service;

import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessStage;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement for supplier governance actions (UX-LIVE-04).
 * Must stay aligned with {@link SupplierGovernanceWorkspaceService} allowedActions.
 */
@Component
public class SupplierGovernanceActionPolicy {
    
    public static final List<String> MANAGER_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "SUPPLIER_MANAGER"
    );
    
    public static final List<String> COMPLIANCE_ACTION_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "COMPLIANCE_MANAGER"
    );

    public void requireActionAllowed(SupplierAccreditationApplication application, String actionKey) {
        if (application == null) {
            throw new BusinessException(
                "Accreditation application is required for action " + actionKey,
                "supplierGovernance.action.notFound");
        }
        
        String status = application.getStatus();
        
        switch (actionKey) {
            case "START_REVIEW" -> requireStartReviewAllowed(application, status);
            case "RUN_COMPLIANCE_CHECK" -> requireRunComplianceCheckAllowed(application, status);
            case "REQUEST_CLARIFICATION" -> requireRequestClarificationAllowed(application, status);
            case "SUBMIT_FOR_APPROVAL" -> requireSubmitForApprovalAllowed(application, status);
            case "REJECT" -> requireRejectAllowed(application, status);
            case "SUSPEND" -> requireSuspendAllowed(application, status);
            case "RENEW" -> requireRenewAllowed(application, status);
            case "ACTIVATE_SUPPLIER" -> requireActivateAllowed();
            case "REQUEST_UNBLOCK" -> requireUnblockAllowed();
            case "ADD_TO_RFQ" -> requireAddToRfqOrTenderAllowed("ADD_TO_RFQ");
            case "ADD_TO_TENDER" -> requireAddToRfqOrTenderAllowed("ADD_TO_TENDER");
            default -> {
                // Navigation actions don't need enforcement
            }
        }
    }

    public void requireSupplierActionAllowed(String actionKey) {
        switch (actionKey) {
            case "ACTIVATE_SUPPLIER" -> requireActivateAllowed();
            case "REQUEST_UNBLOCK" -> requireUnblockAllowed();
            case "ADD_TO_RFQ" -> requireAddToRfqOrTenderAllowed("ADD_TO_RFQ");
            case "ADD_TO_TENDER" -> requireAddToRfqOrTenderAllowed("ADD_TO_TENDER");
            default -> throw new BusinessException(
                "Unknown supplier governance action: " + actionKey,
                "supplierGovernance.action.unknown",
                actionKey);
        }
    }


    public void requireStagePrerequisitesSatisfied(
        StageKey targetStage,
        SupplierReadinessSummary readiness
    ) {
        StageKey prerequisite = prerequisiteOf(targetStage);
        if (prerequisite == null) {
            return; // MASTER_PROFILE has no prerequisite
        }
        ReadinessStage prerequisiteStage = findStage(readiness, prerequisite);

        // A locked stage reports its immediate predecessor, even when that predecessor
        // is itself locked. Follow the chain so the caller sees the step they can act on.
        while (prerequisiteStage.locked() && prerequisiteOf(prerequisite) != null) {
            prerequisite = prerequisiteOf(prerequisite);
            prerequisiteStage = findStage(readiness, prerequisite);
        }

        if (prerequisiteStage.state() != StageState.COMPLETED) {
            String missingRequirements = prerequisiteStage.blockers().stream()
                .filter(blocker -> !"LOCKED_BY_PREREQUISITE".equals(blocker.code()))
                .map(blocker -> blocker.requirementCode())
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
            throw new BusinessException(
                "Stage " + targetStage.name() + " is locked. "
                    + "Complete " + prerequisite.name() + " first (current state: "
                    + prerequisiteStage.state().name() + ")"
                    + (missingRequirements.isEmpty() ? "." : ". Requirements: " + missingRequirements + "."),
                "supplierGovernance.stage.prerequisiteNotMet",
                prerequisite.name(),
                targetStage.name(),
                prerequisiteStage.state().name());
        }
    }

    public void requireStageCompleted(StageKey requiredStage, StageKey targetStage,
                                      SupplierReadinessSummary readiness) {
        ReadinessStage stage = findStage(readiness, requiredStage);
        if (stage.state() != StageState.COMPLETED) {
            throw new BusinessException(
                "Stage " + targetStage.name() + " is locked. Complete " + requiredStage.name()
                    + " first (current state: " + stage.state().name() + ").",
                "supplierGovernance.stage.prerequisiteNotMet",
                requiredStage.name(), targetStage.name(), stage.state().name());
        }
    }

    private ReadinessStage findStage(SupplierReadinessSummary readiness, StageKey key) {
        return readiness.stages().stream()
            .filter(stage -> stage.key() == key)
            .findFirst()
            .orElseThrow(() -> new BusinessException(
                "Prerequisite stage " + key + " not found in readiness summary",
                "supplierGovernance.stage.prerequisiteNotFound",
                key.name()));
    }


    private StageKey prerequisiteOf(StageKey stage) {
        return switch (stage) {
            case PORTAL_ACCOUNT  -> StageKey.MASTER_PROFILE;
            case COMPLIANCE_KYC  -> StageKey.PORTAL_ACCOUNT;
            case APPLICATION     -> StageKey.PORTAL_ACCOUNT;
            case EVIDENCE        -> StageKey.APPLICATION;
            case REVIEW          -> StageKey.EVIDENCE;
            case DECISION        -> StageKey.REVIEW;
            case ACTIVATION      -> StageKey.DECISION;
            default              -> null; // MASTER_PROFILE — first stage, no prerequisite
        };
    }

    private void requireStartReviewAllowed(SupplierAccreditationApplication application, String status) {
        if (!List.of("SUBMITTED", "PENDING_REVIEW", "NEEDS_CLARIFICATION").contains(status)) {
            throw new BusinessException(
                "Action START_REVIEW is not allowed for accreditation status " + status,
                "supplierGovernance.action.startReviewNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot start accreditation review",
                "supplierGovernance.action.startReviewRoleDenied");
        }
    }

    private void requireRunComplianceCheckAllowed(SupplierAccreditationApplication application, String status) {
        if (!List.of("SUBMITTED", "UNDER_REVIEW", "NEEDS_CLARIFICATION", "COMPLIANCE_CHECK").contains(status)) {
            throw new BusinessException(
                "Action RUN_COMPLIANCE_CHECK is not allowed for accreditation status " + status,
                "supplierGovernance.action.runComplianceCheckNotAllowed",
                status);
        }
        if (!hasComplianceRole()) {
            throw new BusinessException(
                "Current role cannot run compliance check",
                "supplierGovernance.action.runComplianceCheckRoleDenied");
        }
    }

    private void requireRequestClarificationAllowed(SupplierAccreditationApplication application, String status) {
        if (!List.of("SUBMITTED", "UNDER_REVIEW", "COMPLIANCE_CHECK").contains(status)) {
            throw new BusinessException(
                "Action REQUEST_CLARIFICATION is not allowed for accreditation status " + status,
                "supplierGovernance.action.requestClarificationNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot request clarification",
                "supplierGovernance.action.requestClarificationRoleDenied");
        }
    }

    private void requireSubmitForApprovalAllowed(SupplierAccreditationApplication application, String status) {
        if (!List.of("COMPLIANCE_CHECK", "COMMITTEE_REVIEW").contains(status)) {
            throw new BusinessException(
                "Action SUBMIT_FOR_APPROVAL is not allowed for accreditation status " + status,
                "supplierGovernance.action.submitForApprovalNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot submit for approval",
                "supplierGovernance.action.submitForApprovalRoleDenied");
        }
    }

    private void requireActivateAllowed() {
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot activate supplier",
                "supplierGovernance.action.activateRoleDenied");
        }
    }

    private void requireUnblockAllowed() {
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot request supplier unblock",
                "supplierGovernance.action.unblockRoleDenied");
        }
    }

    private void requireAddToRfqOrTenderAllowed(String actionKey) {
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot perform " + actionKey,
                "supplierGovernance.action.addToSourcingRoleDenied",
                actionKey);
        }
    }

    private void requireRejectAllowed(SupplierAccreditationApplication application, String status) {
        if (!List.of("SUBMITTED", "UNDER_REVIEW", "COMPLIANCE_CHECK", "COMMITTEE_REVIEW", "NEEDS_CLARIFICATION").contains(status)) {
            throw new BusinessException(
                "Action REJECT is not allowed for accreditation status " + status,
                "supplierGovernance.action.rejectNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot reject accreditation",
                "supplierGovernance.action.rejectRoleDenied");
        }
    }

    private void requireSuspendAllowed(SupplierAccreditationApplication application, String status) {
        if (!List.of("APPROVED", "CONDITIONALLY_APPROVED").contains(status)) {
            throw new BusinessException(
                "Action SUSPEND is not allowed for accreditation status " + status,
                "supplierGovernance.action.suspendNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot suspend accreditation",
                "supplierGovernance.action.suspendRoleDenied");
        }
    }

    private void requireRenewAllowed(SupplierAccreditationApplication application, String status) {
        if (!List.of("APPROVED", "CONDITIONALLY_APPROVED", "EXPIRED", "SUSPENDED").contains(status)) {
            throw new BusinessException(
                "Action RENEW is not allowed for accreditation status " + status,
                "supplierGovernance.action.renewNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot renew accreditation",
                "supplierGovernance.action.renewRoleDenied");
        }
    }

    public boolean hasManagerRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return MANAGER_ROLES.stream().anyMatch(roles::contains);
    }

    public boolean hasComplianceRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return COMPLIANCE_ACTION_ROLES.stream().anyMatch(roles::contains);
    }
}

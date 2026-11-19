package com.enterprise.mm.service;

import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.enums.ProcurementMethodDecisionStatus;
import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement for procurement method decision actions (UX-LIVE-04).
 * Approve/reject go through the approval engine and are not gated here; this policy
 * covers the direct mutation entry points: SUBMIT, UPDATE, REVISE, DELETE.
 */
@Component
public class ProcurementMethodDecisionActionPolicy {

    public static final List<String> MUTATE_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST"
    );

    public void requireActionAllowed(ProcurementMethodDecision decision, String actionKey) {
        if (decision == null) {
            throw new BusinessException(
                "Procurement method decision is required for action " + actionKey,
                "procurementMethodDecision.action.notFound");
        }
        String status = decision.getStatus();
        switch (normalize(actionKey)) {
            case "SUBMIT" -> requireSubmitAllowed(status);
            case "UPDATE" -> requireUpdateAllowed(status);
            case "REVISE" -> requireReviseAllowed(status);
            case "DELETE" -> requireDeleteAllowed(status);
            default -> throw new BusinessException(
                "Unknown procurement method decision action: " + actionKey,
                "procurementMethodDecision.action.unknown",
                actionKey);
        }
    }

    private void requireSubmitAllowed(String status) {
        if (!ProcurementMethodDecisionStatus.DRAFT.name().equals(status)) {
            throw new BusinessException(
                "Action SUBMIT is not allowed for procurement method decision status " + status,
                "procurementMethodDecision.action.submitNotAllowed",
                status);
        }
        requireMutateRole("submit");
    }

    private void requireUpdateAllowed(String status) {
        if (!ProcurementMethodDecisionStatus.DRAFT.name().equals(status)
            && !ProcurementMethodDecisionStatus.RETURNED.name().equals(status)) {
            throw new BusinessException(
                "Action UPDATE is not allowed for procurement method decision status " + status,
                "procurementMethodDecision.action.updateNotAllowed",
                status);
        }
        requireMutateRole("update");
    }

    private void requireReviseAllowed(String status) {
        if (!ProcurementMethodDecisionStatus.REJECTED.name().equals(status)) {
            throw new BusinessException(
                "Action REVISE is not allowed for procurement method decision status " + status,
                "procurementMethodDecision.action.reviseNotAllowed",
                status);
        }
        requireMutateRole("revise");
    }

    private void requireDeleteAllowed(String status) {
        if (!ProcurementMethodDecisionStatus.DRAFT.name().equals(status)
            && !ProcurementMethodDecisionStatus.REJECTED.name().equals(status)) {
            throw new BusinessException(
                "Action DELETE is not allowed for procurement method decision status " + status,
                "procurementMethodDecision.action.deleteNotAllowed",
                status);
        }
        requireMutateRole("delete");
    }

    private void requireMutateRole(String action) {
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot " + action + " procurement method decision",
                "procurementMethodDecision.action.roleDenied");
        }
    }

    public boolean hasMutateRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return MUTATE_ROLES.stream().anyMatch(roles::contains);
    }

    private String normalize(String actionKey) {
        return actionKey == null ? "" : actionKey.trim().toUpperCase(Locale.ROOT);
    }
}

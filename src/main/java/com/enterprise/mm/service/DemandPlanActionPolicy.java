package com.enterprise.mm.service;

import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.DemandPlanWorkspaceActionKey;
import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement for demand plan workspace actions (UX-LIVE-04).
 * Must stay aligned with {@link DemandPlanWorkspaceService} allowedActions.
 */
@Component
public class DemandPlanActionPolicy {

    public static final List<String> MUTATE_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST",
        "DEPARTMENT_HEAD",
        "REQUESTER"
    );
    private static final List<String> DEPARTMENT_ORIGINATOR_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST",
        "DEPARTMENT_HEAD",
        "DEPARTMENT_WORKER",
        "REQUESTER"
    );

    public void requireActionAllowed(DemandPlan plan, DemandPlanWorkspaceActionKey actionKey) {
        if (plan == null) {
            throw new BusinessException(
                "Demand plan is required for action " + actionKey,
                "demandPlan.action.notFound");
        }
        String status = plan.getStatus();
        switch (actionKey) {
            case START_COLLECTION -> requireStartCollectionAllowed(status);
            case SUBMIT -> requireSubmitAllowed(status);
            case ACTIVATE -> requireActivateAllowed(status);
            case CANCEL -> requireCancelAllowed(status);
            case CLOSE -> requireCloseAllowed(status);
            case CREATE_PURCHASE_REQUEST -> requireCreatePurchaseRequestAllowed(status);
            case EDIT -> requireEditAllowed(status);
            case DELETE -> requireDeleteAllowed(status);
            default -> {
                // Navigation actions don't need enforcement
            }
        }
    }

    private void requireStartCollectionAllowed(String status) {
        if (!DemandPlanStatus.DRAFT.name().equals(status)) {
            throw new BusinessException(
                "Action START_COLLECTION is not allowed for demand plan status " + status,
                "demandPlan.action.startCollectionNotAllowed",
                status);
        }
        requireDepartmentOriginatorRole("start collection for");
    }

    private void requireSubmitAllowed(String status) {
        if (!DemandPlanStatus.DRAFT.name().equals(status) && !DemandPlanStatus.COLLECTING.name().equals(status)) {
            throw new BusinessException(
                "Action SUBMIT is not allowed for demand plan status " + status,
                "demandPlan.action.submitNotAllowed",
                status);
        }
        requireDepartmentOriginatorRole("submit");
    }

    private void requireActivateAllowed(String status) {
        if (!DemandPlanStatus.APPROVED.name().equals(status)) {
            throw new BusinessException(
                "Action ACTIVATE is not allowed for demand plan status " + status,
                "demandPlan.action.activateNotAllowed",
                status);
        }
        requireMutateRole("activate");
    }

    private void requireCancelAllowed(String status) {
        if (DemandPlanStatus.CLOSED.name().equals(status)) {
            throw new BusinessException(
                "Action CANCEL is not allowed for demand plan status " + status,
                "demandPlan.action.cancelNotAllowed",
                status);
        }
        requireMutateRole("cancel");
    }

    private void requireCloseAllowed(String status) {
        if (!DemandPlanStatus.APPROVED.name().equals(status) && !DemandPlanStatus.ACTIVE.name().equals(status)) {
            throw new BusinessException(
                "Action CLOSE is not allowed for demand plan status " + status,
                "demandPlan.action.closeNotAllowed",
                status);
        }
        requireMutateRole("close");
    }

    private void requireCreatePurchaseRequestAllowed(String status) {
        if (!DemandPlanStatus.APPROVED.name().equals(status) && !DemandPlanStatus.ACTIVE.name().equals(status)) {
            throw new BusinessException(
                "Action CREATE_PURCHASE_REQUEST is not allowed for demand plan status " + status,
                "demandPlan.action.createPurchaseRequestNotAllowed",
                status);
        }
        requireMutateRole("create purchase requests from");
    }

    private void requireEditAllowed(String status) {
        if (!DemandPlanStatus.DRAFT.name().equals(status) && !DemandPlanStatus.COLLECTING.name().equals(status)) {
            throw new BusinessException(
                "Demand plan must be DRAFT or COLLECTING to be edited, current status is " + status,
                "demandPlan.action.editNotAllowed",
                status);
        }
        requireDepartmentOriginatorRole("edit");
    }

    private void requireDeleteAllowed(String status) {
        if (!DemandPlanStatus.DRAFT.name().equals(status)) {
            throw new BusinessException(
                "Action DELETE is not allowed for demand plan status " + status,
                "demandPlan.action.deleteNotAllowed",
                status);
        }
        requireDepartmentOriginatorRole("delete");
    }

    private void requireMutateRole(String action) {
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot " + action + " demand plan",
                "demandPlan.action.roleDenied");
        }
    }

    private void requireDepartmentOriginatorRole(String action) {
        if (!hasDepartmentOriginatorRole()) {
            throw new BusinessException(
                "Current role cannot " + action + " demand plan",
                "demandPlan.action.roleDenied");
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

    public boolean hasDepartmentOriginatorRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return DEPARTMENT_ORIGINATOR_ROLES.stream().anyMatch(roles::contains);
    }
}

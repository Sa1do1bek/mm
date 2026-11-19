package com.enterprise.mm.service;

import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.PurchaseRequestWorkspaceActionKey;
import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement for purchase-request workspace actions (UX-LIVE-04).
 */
@Component
public class PurchaseRequestActionPolicy {
    public static final List<String> SOURCING_ROLES = List.of(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST"
    );
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

    public void requireActionAllowed(PurchaseRequest request, PurchaseRequestWorkspaceActionKey actionKey) {
        if (request == null) {
            throw new BusinessException(
                "Purchase request is required for action " + actionKey,
                "purchaseRequest.action.notFound");
        }
        String status = request.getStatus();
        switch (actionKey) {
            case SUBMIT -> requireSubmitAllowed(status);
            case EDIT -> requireEditAllowed(status);
            case CANCEL -> requireCancelAllowed(status);
            case CREATE_PMD -> requireCreatePmdAllowed(status);
            case DELETE -> requireDeleteAllowed(status);
            default -> {
                // Navigation / read actions are not enforced here.
            }
        }
    }

    private void requireSubmitAllowed(String status) {
        if (!PurchaseRequestStatus.DRAFT.name().equals(status)
            && !PurchaseRequestStatus.RETURNED.name().equals(status)) {
            throw new BusinessException(
                "Action SUBMIT is not allowed for purchase request status " + status,
                "purchaseRequest.action.submitNotAllowed",
                status);
        }
        requireDepartmentOriginatorRole("submit");
    }

    private void requireEditAllowed(String status) {
        if (!PurchaseRequestStatus.DRAFT.name().equals(status)
            && !PurchaseRequestStatus.RETURNED.name().equals(status)) {
            throw new BusinessException(
                "Action EDIT is not allowed for purchase request status " + status,
                "purchaseRequest.action.editNotAllowed",
                status);
        }
        requireDepartmentOriginatorRole("edit");
    }

    private void requireCancelAllowed(String status) {
        if (PurchaseRequestStatus.CANCELLED.name().equals(status)
            || PurchaseRequestStatus.PO_CREATED.name().equals(status)
            || PurchaseRequestStatus.CONVERTED_TO_PO.name().equals(status)) {
            throw new BusinessException(
                "Action CANCEL is not allowed for purchase request status " + status,
                "purchaseRequest.action.cancelNotAllowed",
                status);
        }
        requireMutateRole("cancel");
    }

    private void requireCreatePmdAllowed(String status) {
        if (!PurchaseRequestStatus.APPROVED.name().equals(status)) {
            throw new BusinessException(
                "Action CREATE_PMD is not allowed for purchase request status " + status,
                "purchaseRequest.action.createPmdNotAllowed",
                status);
        }
        if (!hasAnyRole(SOURCING_ROLES)) {
            throw new BusinessException("Current role cannot create PMD", "purchaseRequest.action.roleDenied");
        }
    }

    private void requireDeleteAllowed(String status) {
        if (!PurchaseRequestStatus.DRAFT.name().equals(status)) {
            throw new BusinessException(
                "Action DELETE is not allowed for purchase request status " + status,
                "purchaseRequest.action.deleteNotAllowed",
                status);
        }
        requireDepartmentOriginatorRole("delete");
    }

    private void requireMutateRole(String action) {
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot " + action + " purchase request",
                "purchaseRequest.action.roleDenied");
        }
    }

    private void requireDepartmentOriginatorRole(String action) {
        if (!hasDepartmentOriginatorRole()) {
            throw new BusinessException(
                "Current role cannot " + action + " purchase request",
                "purchaseRequest.action.roleDenied");
        }
    }

    private boolean hasMutateRole() {
        return hasAnyRole(MUTATE_ROLES);
    }

    private boolean hasAnyRole(List<String> allowedRoles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return allowedRoles.stream().anyMatch(roles::contains);
    }

    private boolean hasDepartmentOriginatorRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return DEPARTMENT_ORIGINATOR_ROLES.stream().anyMatch(roles::contains);
    }
}

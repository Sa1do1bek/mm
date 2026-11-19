package com.enterprise.mm.service;

import com.enterprise.mm.entity.InventoryPeriodClose;
import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement for inventory period close workspace actions (UX-LIVE-04).
 * Must stay aligned with {@link InventoryPeriodCloseWorkspaceService} allowedActions.
 */
@Component
public class InventoryPeriodCloseActionPolicy {
    
    public static final List<String> FINANCE_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "FINANCE_CONTROLLER",
        "ACCOUNTANT"
    );
    
    public static final List<String> REOPEN_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "FINANCE_CONTROLLER"
    );

    public void requireActionAllowed(InventoryPeriodClose period, String actionKey) {
        if (period == null) {
            throw new BusinessException(
                "Inventory period close is required for action " + actionKey,
                "inventoryPeriodClose.action.notFound");
        }
        
        String status = period.getStatus();
        
        switch (actionKey) {
            case "CLOSE" -> requireCloseAllowed(period, status);
            case "REOPEN" -> requireReopenAllowed(period, status);
            case "CALCULATE" -> requireCalculateAllowed(period, status);
            case "PREPARE_RECONCILIATION" -> requirePrepareReconciliationAllowed(period, status);
            case "RECONCILE" -> requireReconcileAllowed(period, status);
            case "POST_RECONCILIATION" -> requirePostReconciliationAllowed(period, status);
            default -> {
                // Navigation actions don't need enforcement
            }
        }
    }

    private void requireCloseAllowed(InventoryPeriodClose period, String status) {
        if ("CLOSED".equals(status)) {
            throw new BusinessException(
                "Action CLOSE is not allowed for period status " + status,
                "inventoryPeriodClose.action.closeNotAllowed",
                status);
        }
        if (!hasFinanceRole()) {
            throw new BusinessException(
                "Current role cannot close inventory period",
                "inventoryPeriodClose.action.closeRoleDenied");
        }
    }

    private void requireReopenAllowed(InventoryPeriodClose period, String status) {
        if (!"CLOSED".equals(status)) {
            throw new BusinessException(
                "Action REOPEN is not allowed for period status " + status,
                "inventoryPeriodClose.action.reopenNotAllowed",
                status);
        }
        if (!hasReopenRole()) {
            throw new BusinessException(
                "Current role cannot reopen inventory period",
                "inventoryPeriodClose.action.reopenRoleDenied");
        }
    }

    private void requireCalculateAllowed(InventoryPeriodClose period, String status) {
        if ("CLOSED".equals(status)) {
            throw new BusinessException(
                "Action CALCULATE is not allowed for period status " + status,
                "inventoryPeriodClose.action.calculateNotAllowed",
                status);
        }
        if (!hasFinanceRole()) {
            throw new BusinessException(
                "Current role cannot calculate inventory period",
                "inventoryPeriodClose.action.calculateRoleDenied");
        }
    }

    private void requirePrepareReconciliationAllowed(InventoryPeriodClose period, String status) {
        if ("CLOSED".equals(status)) {
            throw new BusinessException(
                "Action PREPARE_RECONCILIATION is not allowed for period status " + status,
                "inventoryPeriodClose.action.prepareReconciliationNotAllowed",
                status);
        }
        if (!hasFinanceRole()) {
            throw new BusinessException(
                "Current role cannot prepare reconciliation",
                "inventoryPeriodClose.action.prepareReconciliationRoleDenied");
        }
    }

    private void requireReconcileAllowed(InventoryPeriodClose period, String status) {
        if ("CLOSED".equals(status)) {
            throw new BusinessException(
                "Action RECONCILE is not allowed for period status " + status,
                "inventoryPeriodClose.action.reconcileNotAllowed",
                status);
        }
        if (!hasFinanceRole()) {
            throw new BusinessException(
                "Current role cannot reconcile inventory period",
                "inventoryPeriodClose.action.reconcileRoleDenied");
        }
    }

    private void requirePostReconciliationAllowed(InventoryPeriodClose period, String status) {
        if ("CLOSED".equals(status)) {
            throw new BusinessException(
                "Action POST_RECONCILIATION is not allowed for period status " + status,
                "inventoryPeriodClose.action.postReconciliationNotAllowed",
                status);
        }
        if (!hasFinanceRole()) {
            throw new BusinessException(
                "Current role cannot post reconciliation",
                "inventoryPeriodClose.action.postReconciliationRoleDenied");
        }
    }

    public boolean hasFinanceRole() {
        return hasAnyRole(FINANCE_ROLES);
    }

    public void requireFinanceRoleFor(String actionKey) {
        if (!hasFinanceRole()) {
            throw new BusinessException(
                "Current role cannot perform inventory period action " + actionKey,
                "inventoryPeriodClose.action.roleDenied",
                actionKey);
        }
    }

    public boolean hasReopenRole() {
        return hasAnyRole(REOPEN_ROLES);
    }

    private boolean hasAnyRole(List<String> roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> userRoles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return roles.stream().anyMatch(userRoles::contains);
    }
}

package com.enterprise.mm.service;

import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.enums.NonMovingStockCaseStatus;
import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement for non-moving stock workspace actions (UX-LIVE-04).
 * Must stay aligned with {@link NonMovingStockWorkspaceService} allowedActions.
 */
@Component
public class NonMovingStockActionPolicy {
    
    public static final List<String> MANAGER_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "WAREHOUSE_MANAGER",
        "WAREHOUSE_HEAD"
    );
    
    public static final List<String> MM_MANAGER_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER"
    );
    
    public static final List<String> DECISION_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "FINANCE_CONTROLLER",
        "ACCOUNTANT"
    );
    
    public static final List<String> EXECUTION_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "WAREHOUSE_MANAGER",
        "WAREHOUSE_HEAD",
        "FINANCE_CONTROLLER",
        "ACCOUNTANT"
    );
    
    public static final List<String> CLOSURE_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "FINANCE_CONTROLLER",
        "ACCOUNTANT"
    );

    public void requireActionAllowed(NonMovingStockCase stockCase, String actionKey) {
        if (stockCase == null) {
            throw new BusinessException(
                "Non-moving stock case is required for action " + actionKey,
                "nonMovingStock.action.notFound");
        }
        
        String status = stockCase.getStatus();
        
        switch (actionKey) {
            case "START_REVIEW" -> requireStartReviewAllowed(stockCase, status);
            case "SEND_TO_COMMISSION" -> requireSendToCommissionAllowed(stockCase, status);
            case "RECORD_DECISION" -> requireRecordDecisionAllowed(stockCase, status);
            case "DEFER_REVIEW" -> requireDeferReviewAllowed(stockCase, status);
            case "CANCEL" -> requireCancelAllowed(stockCase, status);
            case "CLOSE" -> requireCloseAllowed(stockCase, status);
            case "EXECUTE_DECISION" -> requireExecuteDecisionAllowed(stockCase, status);
            case "REOPEN" -> requireReopenAllowed(stockCase, status);
            default -> {
                // Navigation actions don't need enforcement
            }
        }
    }

    private void requireStartReviewAllowed(NonMovingStockCase stockCase, String status) {
        if (!NonMovingStockCaseStatus.CASE_CREATED.name().equals(status) 
            && !NonMovingStockCaseStatus.REVIEW_DEFERRED.name().equals(status)) {
            throw new BusinessException(
                "Action START_REVIEW is not allowed for case status " + status,
                "nonMovingStock.action.startReviewNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot start non-moving stock review",
                "nonMovingStock.action.startReviewRoleDenied");
        }
    }

    private void requireSendToCommissionAllowed(NonMovingStockCase stockCase, String status) {
        if (!NonMovingStockCaseStatus.UNDER_REVIEW.name().equals(status)) {
            throw new BusinessException(
                "Action SEND_TO_COMMISSION is not allowed for case status " + status,
                "nonMovingStock.action.sendToCommissionNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot send to commission",
                "nonMovingStock.action.sendToCommissionRoleDenied");
        }
    }

    private void requireRecordDecisionAllowed(NonMovingStockCase stockCase, String status) {
        if (!NonMovingStockCaseStatus.UNDER_REVIEW.name().equals(status)
            && !NonMovingStockCaseStatus.COMMISSION_REVIEW.name().equals(status)) {
            throw new BusinessException(
                "Action RECORD_DECISION is not allowed for case status " + status,
                "nonMovingStock.action.recordDecisionNotAllowed",
                status);
        }
        if (!hasDecisionRole()) {
            throw new BusinessException(
                "Current role cannot record decision",
                "nonMovingStock.action.recordDecisionRoleDenied");
        }
    }

    private void requireDeferReviewAllowed(NonMovingStockCase stockCase, String status) {
        if (!NonMovingStockCaseStatus.UNDER_REVIEW.name().equals(status)) {
            throw new BusinessException(
                "Action DEFER_REVIEW is not allowed for case status " + status,
                "nonMovingStock.action.deferReviewNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot defer review",
                "nonMovingStock.action.deferReviewRoleDenied");
        }
    }

    private void requireCancelAllowed(NonMovingStockCase stockCase, String status) {
        if (NonMovingStockCaseStatus.CLOSED.name().equals(status)
            || NonMovingStockCaseStatus.CANCELLED.name().equals(status)) {
            throw new BusinessException(
                "Action CANCEL is not allowed for case status " + status,
                "nonMovingStock.action.cancelNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot cancel non-moving stock case",
                "nonMovingStock.action.cancelRoleDenied");
        }
    }

    private void requireCloseAllowed(NonMovingStockCase stockCase, String status) {
        if (!NonMovingStockCaseStatus.EXECUTED.name().equals(status)) {
            throw new BusinessException(
                "Action CLOSE is not allowed for case status " + status,
                "nonMovingStock.action.closeNotAllowed",
                status);
        }
        if (!hasClosureRole()) {
            throw new BusinessException(
                "Current role cannot close non-moving stock case",
                "nonMovingStock.action.closeRoleDenied");
        }
    }

    private void requireExecuteDecisionAllowed(NonMovingStockCase stockCase, String status) {
        if (!NonMovingStockCaseStatus.DECISION_APPROVED.name().equals(status)) {
            throw new BusinessException(
                "Action EXECUTE_DECISION is not allowed for case status " + status,
                "nonMovingStock.action.executeDecisionNotAllowed",
                status);
        }
        if (!hasExecutionRole()) {
            throw new BusinessException(
                "Current role cannot execute decision",
                "nonMovingStock.action.executeDecisionRoleDenied");
        }
    }

    private void requireReopenAllowed(NonMovingStockCase stockCase, String status) {
        if (!NonMovingStockCaseStatus.CLOSED.name().equals(status)
            && !NonMovingStockCaseStatus.CANCELLED.name().equals(status)) {
            throw new BusinessException(
                "Action REOPEN is not allowed for case status " + status,
                "nonMovingStock.action.reopenNotAllowed",
                status);
        }
        if (!hasManagerRole()) {
            throw new BusinessException(
                "Current role cannot reopen non-moving stock case",
                "nonMovingStock.action.reopenRoleDenied");
        }
    }

    public boolean hasManagerRole() {
        return hasAnyRole(MANAGER_ROLES);
    }

    public boolean hasDecisionRole() {
        return hasAnyRole(DECISION_ROLES);
    }

    public boolean hasExecutionRole() {
        return hasAnyRole(EXECUTION_ROLES);
    }

    public boolean hasClosureRole() {
        return hasAnyRole(CLOSURE_ROLES);
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

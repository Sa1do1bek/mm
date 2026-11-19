package com.enterprise.mm.service;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.PurchaseOrderWorkspaceActionKey;
import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement for purchase order workspace actions (UX-LIVE-04).
 * Must stay aligned with {@link PurchaseOrderWorkspaceService} allowedActions.
 */
@Component
public class PurchaseOrderActionPolicy {
    
    public static final List<String> MUTATE_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST"
    );

    public void requireActionAllowed(PurchaseOrder purchaseOrder, PurchaseOrderWorkspaceActionKey actionKey) {
        if (purchaseOrder == null) {
            throw new BusinessException(
                "Purchase order is required for action " + actionKey,
                "purchaseOrder.action.notFound");
        }
        
        String status = purchaseOrder.getStatus();
        
        switch (actionKey) {
            case SUBMIT -> requireSubmitAllowed(purchaseOrder, status);
            case EDIT -> requireEditAllowed(purchaseOrder, status);
            case DELETE -> requireDeleteAllowed(purchaseOrder, status);
            case CANCEL -> requireCancelAllowed(purchaseOrder, status);
            case SEND -> requireSendAllowed(purchaseOrder, status);
            case CREATE_INVOICE -> requireCreateInvoiceAllowed(purchaseOrder, status);
            case SUBMIT_PAYMENT -> requireSubmitPaymentAllowed(purchaseOrder, status);
            default -> {
                // Navigation actions don't need enforcement
            }
        }
    }

    private void requireSubmitAllowed(PurchaseOrder purchaseOrder, String status) {
        if (!PurchaseOrderStatus.DRAFT.name().equals(status) && !PurchaseOrderStatus.RETURNED.name().equals(status)) {
            throw new BusinessException(
                "Action SUBMIT is not allowed for purchase order status " + status,
                "purchaseOrder.action.submitNotAllowed",
                status);
        }
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot submit purchase order",
                "purchaseOrder.action.submitRoleDenied");
        }
    }

    private void requireEditAllowed(PurchaseOrder purchaseOrder, String status) {
        if (!PurchaseOrderStatus.DRAFT.name().equals(status) && !PurchaseOrderStatus.RETURNED.name().equals(status)) {
            throw new BusinessException(
                "Action EDIT is not allowed for purchase order status " + status,
                "purchaseOrder.action.editNotAllowed",
                status);
        }
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot edit purchase order",
                "purchaseOrder.action.editRoleDenied");
        }
    }

    private void requireDeleteAllowed(PurchaseOrder purchaseOrder, String status) {
        if (!PurchaseOrderStatus.DRAFT.name().equals(status)) {
            throw new BusinessException(
                "Action DELETE is not allowed for purchase order status " + status,
                "purchaseOrder.action.deleteNotAllowed",
                status);
        }
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot delete purchase order",
                "purchaseOrder.action.deleteRoleDenied");
        }
    }

    private void requireCancelAllowed(PurchaseOrder purchaseOrder, String status) {
        if (PurchaseOrderStatus.CANCELLED.name().equals(status)) {
            throw new BusinessException(
                "Action CANCEL is not allowed for purchase order status " + status,
                "purchaseOrder.action.cancelNotAllowed",
                status);
        }
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot cancel purchase order",
                "purchaseOrder.action.cancelRoleDenied");
        }
    }

    private void requireSendAllowed(PurchaseOrder purchaseOrder, String status) {
        if (!PurchaseOrderStatus.APPROVED.name().equals(status)) {
            throw new BusinessException(
                "Action SEND is not allowed for purchase order status " + status,
                "purchaseOrder.action.sendNotAllowed",
                status);
        }
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot send purchase order",
                "purchaseOrder.action.sendRoleDenied");
        }
    }

    private void requireCreateInvoiceAllowed(PurchaseOrder purchaseOrder, String status) {
        // Invoice creation is allowed for APPROVED, SENT, PARTIALLY_RECEIVED, RECEIVED, PARTIALLY_COMPLETED, COMPLETED
        // but the workspace checks evidence availability and remaining invoiceable amount
        // This policy only checks status and role
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot create invoice",
                "purchaseOrder.action.createInvoiceRoleDenied");
        }
    }

    private void requireSubmitPaymentAllowed(PurchaseOrder purchaseOrder, String status) {
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot submit payment",
                "purchaseOrder.action.submitPaymentRoleDenied");
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
}

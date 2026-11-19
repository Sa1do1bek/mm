package com.enterprise.mm.warehouse.goodsincome.service;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
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
 * Server-side enforcement for goods-income RECEIVE (UX-LIVE-04).
 * Must stay aligned with {@link GoodsIncomeWorkspaceService} allowedActions.
 */
@Component
public class GoodsIncomeReceiveActionPolicy {
    public static final String RECEIVE = "RECEIVE";

    public static final List<String> RECEIVE_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "WAREHOUSE_MANAGER",
        "WAREHOUSE_OPERATOR",
        "WAREHOUSE_HEAD",
        "WAREHOUSE_KEEPER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST"
    );

    private static final Set<String> RECEIVABLE_STATUSES = Set.of(
        PurchaseOrderStatus.SENT.name(),
        PurchaseOrderStatus.PARTIALLY_RECEIVED.name()
    );

    public void requireReceiveAllowed(PurchaseOrder purchaseOrder) {
        if (purchaseOrder == null) {
            throw new BusinessException(
                "Purchase order is required for goods receipt",
                "goodsIncome.action.receiveNotAllowed");
        }
        if (purchaseOrder.getRequestType() != RequestType.GOODS) {
            throw new BusinessException(
                "Warehouse receipt is only allowed for GOODS purchase orders",
                "goodsIncome.po.notGoods",
                purchaseOrder.getId());
        }
        String status = purchaseOrder.getStatus();
        if (status == null || !RECEIVABLE_STATUSES.contains(status)) {
            throw new BusinessException(
                "Action RECEIVE is not allowed for purchase order status " + status,
                "goodsIncome.action.receiveNotAllowed",
                status);
        }
        if (!hasReceiveRole()) {
            throw new BusinessException(
                "Current role cannot post a goods receipt",
                "goodsIncome.action.receiveRoleDenied");
        }
    }

    public boolean hasReceiveRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return RECEIVE_ROLES.stream().anyMatch(roles::contains);
    }
}

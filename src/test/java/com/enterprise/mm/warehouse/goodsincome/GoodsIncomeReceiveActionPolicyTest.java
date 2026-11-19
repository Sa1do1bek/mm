package com.enterprise.mm.warehouse.goodsincome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeReceiveActionPolicy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class GoodsIncomeReceiveActionPolicyTest {
    private GoodsIncomeReceiveActionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new GoodsIncomeReceiveActionPolicy();
        authenticate("WAREHOUSE_OPERATOR");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsReceiveForSentGoodsPurchaseOrder() {
        PurchaseOrder po = purchaseOrder(PurchaseOrderStatus.SENT.name(), RequestType.GOODS);
        assertThatCode(() -> policy.requireReceiveAllowed(po)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonReceivableStatusWithTypedKey() {
        PurchaseOrder po = purchaseOrder(PurchaseOrderStatus.DRAFT.name(), RequestType.GOODS);
        assertThatThrownBy(() -> policy.requireReceiveAllowed(po))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException business = (BusinessException) ex;
                assertThat(business.getMessageKey()).isEqualTo("goodsIncome.action.receiveNotAllowed");
                assertThat(business.getMessage()).contains("DRAFT");
            });
    }

    @Test
    void rejectsWorksPurchaseOrderWithTypedKey() {
        PurchaseOrder po = purchaseOrder(PurchaseOrderStatus.SENT.name(), RequestType.SERVICE);
        assertThatThrownBy(() -> policy.requireReceiveAllowed(po))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("goodsIncome.po.notGoods"));
    }

    @Test
    void rejectsMissingReceiveRoleWithTypedKey() {
        authenticate("VIEWER");
        PurchaseOrder po = purchaseOrder(PurchaseOrderStatus.SENT.name(), RequestType.GOODS);
        assertThatThrownBy(() -> policy.requireReceiveAllowed(po))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("goodsIncome.action.receiveRoleDenied"));
    }

    private PurchaseOrder purchaseOrder(String status, RequestType requestType) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(UUID.randomUUID());
        po.setStatus(status);
        po.setRequestType(requestType);
        return po;
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "tester",
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        ));
    }
}

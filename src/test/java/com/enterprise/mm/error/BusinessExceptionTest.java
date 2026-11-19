package com.enterprise.mm.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UX-LIVE-01: BusinessException can carry structured context for API consumers. */
class BusinessExceptionTest {

    @Test
    void defaultConstructorsHaveEmptyContext() {
        assertThat(new BusinessException("boom").getContext()).isEmpty();
        assertThat(new BusinessException("boom", "some.key", "arg").getContext()).isEmpty();
    }

    @Test
    void contextConstructorExposesImmutableCopyOfContext() {
        UUID purchaseOrderId = UUID.randomUUID();
        Map<String, Object> context = Map.of("purchaseOrderId", purchaseOrderId, "purchaseOrderCode", "PO-1");

        BusinessException exception = new BusinessException(
            "Only SENT or PARTIALLY_RECEIVED purchase order can be received",
            "goodsIncome.action.receiveNotAllowed",
            context,
            "DRAFT"
        );

        assertThat(exception.getContext()).containsEntry("purchaseOrderId", purchaseOrderId);
        assertThat(exception.getContext()).containsEntry("purchaseOrderCode", "PO-1");
        assertThat(exception.getMessageKey()).isEqualTo("goodsIncome.action.receiveNotAllowed");
        assertThat(exception.getMessageArgs()).containsExactly("DRAFT");
    }

    @Test
    void nullOrEmptyContextNormalizesToEmptyMap() {
        BusinessException withNullContext = new BusinessException("boom", "key", null, "arg");
        BusinessException withEmptyContext = new BusinessException("boom", "key", Map.of(), "arg");

        assertThat(withNullContext.getContext()).isEmpty();
        assertThat(withEmptyContext.getContext()).isEmpty();
    }
}

package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.fulfillment.FulfillmentQuantities;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FulfillmentQuantitiesTest {

    @Test
    void separatesPhysicalReceiptRemainingFromAcceptedFulfillmentRemaining() {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setQuantity(new BigDecimal("10"));
        item.setReceivedQuantity(new BigDecimal("10"));
        item.setAcceptedQuantity(new BigDecimal("4"));
        item.setRejectedQuantity(new BigDecimal("1"));
        item.setDamagedQuantity(BigDecimal.ZERO);

        assertThat(FulfillmentQuantities.physicalReceiptRemaining(item)).isEqualByComparingTo("0");
        assertThat(FulfillmentQuantities.acceptedFulfillmentRemaining(item)).isEqualByComparingTo("6");
    }

    @Test
    void rejectedAndDamagedAreAlreadyIncludedInPhysicalReceived() {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setQuantity(new BigDecimal("10"));
        item.setReceivedQuantity(new BigDecimal("6"));
        item.setAcceptedQuantity(new BigDecimal("5"));
        item.setRejectedQuantity(BigDecimal.ONE);
        item.setDamagedQuantity(BigDecimal.ZERO);
        assertThat(FulfillmentQuantities.physicalReceiptRemaining(item)).isEqualByComparingTo("4");
        item.setReceivedQuantity(new BigDecimal("10"));
        item.setAcceptedQuantity(new BigDecimal("9"));
        assertThat(FulfillmentQuantities.physicalReceiptRemaining(item)).isZero();
        assertThat(FulfillmentQuantities.acceptedFulfillmentRemaining(item)).isEqualByComparingTo("1");
    }

    @Test
    void nonMaterialLinesHaveNoGoodsReceiptRemaining() {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setLineType(ProcurementLineType.SERVICE);
        item.setQuantity(new BigDecimal("10"));

        assertThat(FulfillmentQuantities.physicalReceiptRemaining(item)).isEqualByComparingTo("0");
        assertThat(FulfillmentQuantities.acceptedFulfillmentRemaining(item)).isEqualByComparingTo("0");
    }
}

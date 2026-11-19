package com.enterprise.mm.fulfillment;

import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.ProcurementLineType;
import java.math.BigDecimal;

public final class FulfillmentQuantities {
    private FulfillmentQuantities() {
    }

    public static BigDecimal physicalReceiptRemaining(PurchaseOrderItem item) {
        if (!isMaterialLine(item)) {
            return BigDecimal.ZERO;
        }
        return nvl(item.getQuantity())
            .subtract(nvl(item.getReceivedQuantity()))
            .max(BigDecimal.ZERO);
    }

    public static BigDecimal acceptedFulfillmentRemaining(PurchaseOrderItem item) {
        if (!isMaterialLine(item)) {
            return BigDecimal.ZERO;
        }
        return nvl(item.getQuantity())
            .subtract(nvl(item.getAcceptedQuantity()))
            .max(BigDecimal.ZERO);
    }

    private static boolean isMaterialLine(PurchaseOrderItem item) {
        if (item == null) {
            return false;
        }
        ProcurementLineType lineType = item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
        return lineType == ProcurementLineType.MATERIAL;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

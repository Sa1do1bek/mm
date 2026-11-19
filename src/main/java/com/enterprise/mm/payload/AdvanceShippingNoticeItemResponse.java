package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdvanceShippingNoticeItemResponse extends BaseRecordDto {
    private UUID asnId;
    private String asnCode;
    private String asnName;
    private UUID purchaseOrderItemId;
    private String purchaseOrderItemCode;
    private String purchaseOrderItemName;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private BigDecimal quantity;
    private BigDecimal receivedQuantity;
    private BigDecimal cancelledQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal packageCount;
    private String packageType;
    private BigDecimal grossWeight;
    private BigDecimal netWeight;
    private String batchNumber;
    private String serialNumbers;
    private LocalDate expirationDate;
}

package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceOrderResponse extends BaseRecordDto {
    private UUID serviceRequestId;
    private String serviceRequestCode;
    private String serviceRequestName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private String serviceType;
    private BigDecimal totalAmount;
    private String currencyCode;
}

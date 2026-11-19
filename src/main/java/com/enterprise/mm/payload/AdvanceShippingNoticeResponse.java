package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdvanceShippingNoticeResponse extends BaseRecordDto {
    private String asnNumber;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private UUID incotermId;
    private String incotermCode;
    private String incotermName;
    private LocalDate noticeDate;
    private LocalDate expectedShipDate;
    private LocalDate expectedArrivalDate;
    private String transportMode;
    private String carrierName;
    private String vehicleNumber;
    private String containerNumber;
    private String trackingNumber;
    private String originCountry;
    private String destinationCountry;
    private String loadingPoint;
    private String unloadingPoint;
    private BigDecimal totalQuantity;
    private BigDecimal totalAmount;
    private String currencyCode;
    private LocalDateTime sentAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime receivedAt;
}
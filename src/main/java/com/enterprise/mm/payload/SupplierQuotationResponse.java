package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierQuotationResponse extends BaseRecordDto {
    private UUID rfqId;
    private String rfqCode;
    private String rfqName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private BigDecimal totalAmount;
    private String currencyCode;
    private LocalDateTime submittedAt;
    private boolean selected;
    private BigDecimal technicalScore;
    private BigDecimal deliveryScore;
    private BigDecimal priceScore;
    private BigDecimal totalScore;
    private String selectionReason;
    private boolean selectCreatePoReady;
    private String selectCreatePoBlockedReason;
    private boolean awardContractReady;
    private String awardContractBlockedReason;
}

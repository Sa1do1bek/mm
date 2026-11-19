package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractResponse extends BaseRecordDto {
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String contractNumber;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal limitAmount;
    private BigDecimal usedAmount;
    private BigDecimal serviceUsedAmount;
    private BigDecimal remainingAmount;
    private String currencyCode;
    private UUID sourceRfqId;
    private UUID sourceQuotationId;
    private boolean purchaseOrderReady;
    private String purchaseOrderBlockedReason;
}

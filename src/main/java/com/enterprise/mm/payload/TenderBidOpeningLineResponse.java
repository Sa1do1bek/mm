package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderBidOpeningLineResponse extends BaseRecordDto {
    private UUID protocolId;
    private String protocolCode;
    private String protocolName;
    private UUID rfqId;
    private String rfqCode;
    private String rfqName;
    private UUID quotationId;
    private String quotationCode;
    private String quotationName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private LocalDateTime submittedAt;
    private BigDecimal totalAmount;
    private String currencyCode;
    private boolean acceptedForEvaluation;
    private String rejectionReason;
}
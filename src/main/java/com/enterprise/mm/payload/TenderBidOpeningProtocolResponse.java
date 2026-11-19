package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderBidOpeningProtocolResponse extends BaseRecordDto {
    private UUID rfqId;
    private String rfqCode;
    private String rfqName;
    private String protocolNumber;
    private LocalDateTime openedAt;
    private String openedBy;
    private Integer quorumRequired;
    private Integer quorumPresent;
    private boolean quorumConfirmed;
    private Integer quotationCount;
    private Integer acceptedQuotationCount;
    private Integer rejectedQuotationCount;
    private String minutes;
    private LocalDateTime postedAt;
}
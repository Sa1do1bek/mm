package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderConflictCheckResponse extends BaseRecordDto {
    private UUID rfqId;
    private String rfqCode;
    private String rfqName;
    private UUID quotationId;
    private String quotationCode;
    private String quotationName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID commissionMemberId;
    private String commissionMemberCode;
    private String commissionMemberName;
    private UUID relatedPartyId;
    private String relatedPartyCode;
    private String relatedPartyName;
    private boolean conflictFound;
    private String conflictType;
    private String severity;
    private String details;
    private String mitigationAction;
    private boolean resolved;
    private LocalDateTime checkedAt;
}
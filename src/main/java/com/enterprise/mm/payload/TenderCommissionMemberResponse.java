package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderCommissionMemberResponse extends BaseRecordDto {
    private UUID rfqId;
    private String rfqCode;
    private String rfqName;
    private UUID employeeId;
    private String employeeCode;
    private String employeeName;
    private String memberName;
    private String memberRole;
    private BigDecimal votingWeight;
    private boolean conflictDeclarationSigned;
    private LocalDateTime declaredAt;
    private boolean active;
}
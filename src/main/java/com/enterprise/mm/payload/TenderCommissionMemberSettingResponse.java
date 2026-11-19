package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderCommissionMemberSettingResponse extends BaseRecordDto {
    private UUID tenderCommissionId;
    private UUID commissionMemberId;
    private UUID userId;
    private UUID employeeId;
    private String memberName;
    private String role;
    private BigDecimal votingWeight;
    private boolean conflictDeclarationSigned;
    private LocalDateTime declaredAt;
    private boolean active;
}

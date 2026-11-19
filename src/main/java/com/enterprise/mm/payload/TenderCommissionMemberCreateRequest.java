package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderCommissionMemberCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID rfqId;
    private UUID employeeId;
    private String memberName;
    private String memberRole;
    private BigDecimal votingWeight;
    private Boolean conflictDeclarationSigned;
    private LocalDateTime declaredAt;
    private Boolean active;
}

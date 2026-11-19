package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderConflictCheckCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID rfqId;
    private UUID quotationId;
    private UUID supplierId;
    private UUID commissionMemberId;
    private UUID relatedPartyId;
    private boolean conflictFound;
    private String conflictType;
    private String severity;
    private String details;
    private String mitigationAction;
    private boolean resolved;
    private LocalDateTime checkedAt;
}

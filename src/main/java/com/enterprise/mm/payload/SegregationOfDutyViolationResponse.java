package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SegregationOfDutyViolationResponse extends BaseRecordDto {
    private UUID ruleId;
    private String entityType;
    private UUID entityId;
    private String actionCode;
    private String ruleType;
    private String username;
    private String matchedPrincipal;
    private String decision;
    private String severity;
    private LocalDateTime occurredAt;
    private String message;
}
package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SegregationOfDutyViolationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
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

package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SegregationOfDutyRuleCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String entityType;
    private String actionCode;
    private String ruleType;
    private String subjectField;
    private String sourceActionCode;
    private String statusScope;
    private String appliesToRoles;
    private String exemptRoles;
    private boolean activeFlag;
    private boolean allowAdminOverride;
    private String severity;
    private String message;
}

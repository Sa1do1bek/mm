package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SegregationOfDutyRuleResponse extends BaseRecordDto {
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

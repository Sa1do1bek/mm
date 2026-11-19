package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "segregation_of_duty_rules")
public class SegregationOfDutyRule extends BusinessEntity {
    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "action_code", nullable = false, length = 80)
    private String actionCode;

    @Column(name = "rule_type", nullable = false, length = 80)
    private String ruleType = "CREATOR_CANNOT_APPROVE";

    @Column(name = "subject_field", length = 120)
    private String subjectField = "CREATED_BY";

    @Column(name = "source_action_code", length = 80)
    private String sourceActionCode;

    @Column(name = "status_scope", length = 500)
    private String statusScope;

    @Column(name = "applies_to_roles", length = 500)
    private String appliesToRoles;

    @Column(name = "exempt_roles", length = 500)
    private String exemptRoles;

    @Column(name = "active_flag", nullable = false)
    private boolean activeFlag = true;

    @Column(name = "allow_admin_override", nullable = false)
    private boolean allowAdminOverride = true;

    @Column(name = "severity", nullable = false, length = 40)
    private String severity = "BLOCK";

    @Column(name = "message", length = 500)
    private String message;
}

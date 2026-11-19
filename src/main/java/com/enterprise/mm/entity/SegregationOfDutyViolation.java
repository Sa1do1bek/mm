package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "segregation_of_duty_violations")
public class SegregationOfDutyViolation extends BusinessEntity {
    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "entity_type", nullable = false, length = 120)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "action_code", nullable = false, length = 80)
    private String actionCode;

    @Column(name = "rule_type", nullable = false, length = 80)
    private String ruleType;

    @Column(name = "username", nullable = false, length = 120)
    private String username;

    @Column(name = "matched_principal", length = 120)
    private String matchedPrincipal;

    @Column(name = "decision", nullable = false, length = 40)
    private String decision;

    @Column(name = "severity", nullable = false, length = 40)
    private String severity;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "message", length = 500)
    private String message;
}
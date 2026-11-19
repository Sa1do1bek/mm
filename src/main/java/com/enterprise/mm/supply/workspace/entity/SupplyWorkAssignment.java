package com.enterprise.mm.supply.workspace.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "supply_work_assignments",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_supply_work_assignment_entity_active",
        columnNames = {"tenant_id", "company_id", "commitment_key", "is_delete"}))
public class SupplyWorkAssignment extends BusinessEntity {
    @Column(name = "commitment_key", nullable = false, length = 80)
    private String commitmentKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "commitment_kind", nullable = false, length = 20)
    private SupplyCommitmentKind commitmentKind;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "owner_username", length = 120)
    private String ownerUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_role", length = 80)
    private SupplyAssignmentRole ownerRole;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "due_date_source", length = 20)
    private SupplyDueDateSource dueDateSource;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SupplySeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "escalation_state", nullable = false, length = 30)
    private SupplyEscalationState escalationState = SupplyEscalationState.NONE;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(columnDefinition = "text")
    private String reason;
}

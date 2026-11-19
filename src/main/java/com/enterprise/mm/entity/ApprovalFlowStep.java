package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalStepType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "approval_flow_steps",
    uniqueConstraints = @UniqueConstraint(name = "uk_approval_flow_steps_order", columnNames = {"approval_flow_id", "step_order"})
)
public class ApprovalFlowStep extends BusinessEntity {
    @Column(name = "approval_flow_id", nullable = false)
    private UUID approvalFlowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_flow_id", insertable = false, updatable = false)
    private ApprovalFlow approvalFlow;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 40)
    private ApprovalStepType stepType = ApprovalStepType.STANDARD;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "department_id")
    private UUID departmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_type", nullable = false, length = 20)
    private ApprovalAssigneeType assigneeType = ApprovalAssigneeType.ROLE;

    @Column(name = "assignee_role", length = 80)
    private String assigneeRole;

    @Column(name = "assignee_user_id")
    private UUID assigneeUserId;

    @Column(name = "assignee_username", length = 120)
    private String assigneeUsername;

    @Column(name = "can_return", nullable = false)
    private boolean canReturn;

    @Column(name = "is_approver", nullable = false)
    private boolean approver;

    @Column(name = "comment_required", nullable = false)
    private boolean commentRequired = true;
}


package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "approval_tasks")
public class ApprovalTask extends BusinessEntity {
    @Column(name = "approval_instance_id", nullable = false)
    private UUID approvalInstanceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_instance_id", insertable = false, updatable = false)
    private ApprovalInstance approvalInstance;

    @Column(name = "approval_flow_step_id")
    private UUID approvalFlowStepId;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_type", nullable = false, length = 20)
    private ApprovalAssigneeType assigneeType = ApprovalAssigneeType.ROLE;

    @Column(name = "assignee_role", length = 80)
    private String assigneeRole;

    @Column(name = "assignee_user_id")
    private UUID assigneeUserId;

    @Column(name = "assignee_username", length = 120)
    private String assigneeUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ApprovalTaskStatus taskStatus = ApprovalTaskStatus.PENDING;

    @Column(name = "acted_by", length = 120)
    private String actedBy;

    @Column(name = "acted_at")
    private LocalDateTime actedAt;

    @Column(columnDefinition = "text")
    private String commentText;
}

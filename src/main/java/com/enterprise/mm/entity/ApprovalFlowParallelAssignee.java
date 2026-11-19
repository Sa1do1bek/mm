package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "approval_flow_parallel_assignees")
public class ApprovalFlowParallelAssignee extends BusinessEntity {
    @Column(name = "approval_flow_id", nullable = false)
    private UUID approvalFlowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_flow_id", insertable = false, updatable = false)
    private ApprovalFlow approvalFlow;

    @Column(name = "assignee_user_id")
    private UUID assigneeUserId;

    @Column(name = "assignee_username", nullable = false, length = 120)
    private String assigneeUsername;
}

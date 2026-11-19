package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
// Flyway enforces one active instance per entity; archived revision cycles retain their history.
@Table(name = "approval_instances")
public class ApprovalInstance extends BusinessEntity {
    @Column(name = "approval_flow_id", nullable = false)
    private UUID approvalFlowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_flow_id", insertable = false, updatable = false)
    private ApprovalFlow approvalFlow;

    @Column(name = "module_key", nullable = false, length = 80)
    private String moduleKey;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ApprovalInstanceStatus approvalStatus = ApprovalInstanceStatus.DRAFT;

    @Column(name = "current_step_order")
    private Integer currentStepOrder;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}

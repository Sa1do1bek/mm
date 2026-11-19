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
@Table(name = "approval_steps")
public class ApprovalStep extends BusinessEntity {
    @Column(name = "approval_workflow_id", nullable = false)
    private UUID approvalWorkflowId;
    private Integer stepNumber = 1;
    @Column(length = 80)
    private String approverRole;
    @Column(length = 120)
    private String approvedBy;
    private LocalDateTime approvedAt;
    @Column(length = 40)
    private String decision;
    @Column(columnDefinition = "text")
    private String commentText;
}
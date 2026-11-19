package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ApprovalHistoryAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "approval_history")
public class ApprovalHistory extends BusinessEntity {
    @Column(name = "approval_instance_id", nullable = false)
    private UUID approvalInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ApprovalHistoryAction action;

    @Column(name = "actor", length = 120)
    private String actor;

    @Column(name = "from_step_order")
    private Integer fromStepOrder;

    @Column(name = "to_step_order")
    private Integer toStepOrder;

    @Column(columnDefinition = "text")
    private String commentText;

    @Column(name = "action_at", nullable = false)
    private LocalDateTime actionAt = LocalDateTime.now();
}

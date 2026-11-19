package com.enterprise.mm.procurement.command.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "procurement_work_assignments")
public class ProcurementWorkAssignment extends BusinessEntity {
    @Column(name = "purchase_request_id", nullable = false)
    private UUID purchaseRequestId;
    @Column(name = "owner_user_id")
    private UUID ownerUserId;
    @Column(name = "owner_username", length = 120)
    private String ownerUsername;
    @Column(name = "owner_role", length = 80)
    private String ownerRole;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(length = 30)
    private String severity;
    @Column(nullable = false)
    private boolean escalated;
    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;
    @Column(columnDefinition = "text")
    private String reason;
}

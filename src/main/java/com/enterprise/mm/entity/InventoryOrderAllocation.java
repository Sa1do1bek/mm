package com.enterprise.mm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "inventory_order_allocations")
@Getter @Setter
public class InventoryOrderAllocation {
    @Id private UUID id;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "source_keys", nullable = false, columnDefinition = "text") private String sourceKeys;
    @Column(name = "task_type", nullable = false) private String taskType;
    @Column(name = "request_payload", nullable = false, columnDefinition = "text") private String requestPayload;
    @Column(name = "inventory_document_id") private UUID inventoryDocumentId;
    @Column(name = "commission_id") private UUID commissionId;
    @Column(nullable = false) private String status = "OPEN";
    @Column(name = "result_note", columnDefinition = "text") private String resultNote;
    @Column(name = "approver_user_id") private UUID approverUserId;
    @Column(name = "submitted_at") private LocalDateTime submittedAt;
    @Column(name = "approved_at") private LocalDateTime approvedAt;
    @Column(name = "accepted_at") private LocalDateTime acceptedAt;
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "accepted_by") private String acceptedBy;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}

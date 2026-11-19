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
@Table(name = "inventory_orders")
@Getter @Setter
public class InventoryOrder {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(name = "company_id", nullable = false) private UUID companyId;
    @Column(name = "owner_username", nullable = false) private String ownerUsername;
    @Column(name = "source_draft_id", nullable = false) private UUID sourceDraftId;
    @Column(name = "analysis_job_id", nullable = false) private UUID analysisJobId;
    @Column(name = "analysis_payload", nullable = false, columnDefinition = "text") private String analysisPayload;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}

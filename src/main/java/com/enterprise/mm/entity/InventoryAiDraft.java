package com.enterprise.mm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "inventory_ai_drafts")
@Getter
@Setter
public class InventoryAiDraft {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false, length = 80) private String tenantId;
    @Column(name = "company_id", nullable = false, updatable = false) private UUID companyId;
    @Column(name = "owner_username", nullable = false, updatable = false, length = 120) private String ownerUsername;
    @Column(nullable = false, length = 20) private String status = "OPEN";
    @Column(name = "inventory_document_id") private UUID inventoryDocumentId;
    @Column(name = "inventory_order_id") private UUID inventoryOrderId;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Version private Long version = 0L;
}

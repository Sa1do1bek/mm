package com.enterprise.mm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Personal display threshold, independent of company detection policy and case evidence. */
@Getter
@Setter
@Entity
@Table(name = "warehouse_non_moving_preferences", uniqueConstraints = @UniqueConstraint(
    name = "uk_warehouse_non_moving_preference_scope", columnNames = {"tenant_id", "company_id", "user_id"}))
public class WarehouseNonMovingPreference {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false, length = 80)
    private String tenantId;
    @Column(name = "company_id", nullable = false)
    private UUID companyId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "threshold_months", nullable = false)
    private int thresholdMonths;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

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
@Table(name = "audit_logs")
public class AuditLog extends BusinessEntity {
    @Column(length = 120)
    private String entityType;
    @Column(name = "entity_id")
    private UUID entityId;
    @Column(length = 80)
    private String action;
    @Column(length = 120)
    private String username;
    @Column(length = 250, name = "full_name")
    private String fullName;
    @Column(columnDefinition = "text")
    private String details;
    @Column(name = "old_snapshot", columnDefinition = "text")
    private String oldSnapshot;
    @Column(name = "new_snapshot", columnDefinition = "text")
    private String newSnapshot;
    @Column(columnDefinition = "text")
    private String diff;
    private LocalDateTime occurredAt;
    @Column(length = 80)
    private String ipAddress;
}

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
@Table(name = "rfid_tags")
public class RfidTag extends BusinessEntity {
    @Column(length = 160, nullable = false)
    private String tagValue;
    @Column(length = 80, nullable = false)
    private String entityType;
    @Column(nullable = false)
    private UUID entityId;
    @Column(length = 120)
    private String deviceId;
    private LocalDateTime lastSeenAt;
}
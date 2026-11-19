package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuditLogCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String entityType;
    private UUID entityId;
    private String action;
    private String username;
    private String fullName;
    private String details;
    private String oldSnapshot;
    private String newSnapshot;
    private String diff;
    private LocalDateTime occurredAt;
    private String ipAddress;
}

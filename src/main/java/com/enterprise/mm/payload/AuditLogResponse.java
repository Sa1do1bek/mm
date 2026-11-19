package com.enterprise.mm.payload;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuditLogResponse extends BaseRecordDto {
    private String entityType;
    private UUID entityId;
    private String action;
    private String fullName;
    private String details;
    private JsonNode oldSnapshot;
    private JsonNode newSnapshot;
    private JsonNode diff;
    private LocalDateTime occurredAt;
    private String ipAddress;
}

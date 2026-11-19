package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExternalInboundRecordCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String sourceSystem;
    private String dataset;
    private String externalId;
    private String externalCode;
    private String providerStatus;
    private String sourcePath;
    private String payloadHash;
    private String payload;
    private String appliedEntityType;
    private UUID appliedEntityId;
    private LocalDateTime syncedAt;
}

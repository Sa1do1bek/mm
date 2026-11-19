package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExternalInboundRecordResponse extends BaseRecordDto {
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
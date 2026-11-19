package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IntegrationEventResponse extends BaseRecordDto {
    private String direction;
    private String eventType;
    private String aggregateType;
    private UUID aggregateId;
    private String payload;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime lastAttemptAt;
    private String idempotencyKey;
    private String lastError;
    private String externalId;
    private UUID replayOfEventId;
    private String manualReason;
    /** Human document type for recovery drilldown (UX-LIVE-03). */
    private String businessDocumentType;
    private UUID businessDocumentId;
    private String businessDocumentNumber;
    private String businessDocumentName;
    private String businessDocumentModuleKey;
    private String businessDocumentRoute;
    private boolean businessDocumentIdentityAvailable;
}

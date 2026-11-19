package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetEventResponse extends BaseRecordDto {
    private UUID fixedAssetId;
    private String fixedAssetName;
    private String eventType;
    private String commandId;
    private String idempotencyKey;
    private String beforeLifecycleStatus;
    private String afterLifecycleStatus;
    private String beforeCustodyKind;
    private String afterCustodyKind;
    private String beforeCustodyJson;
    private String afterCustodyJson;
    private LocalDate effectiveDate;
    private LocalDateTime recordedAt;
    private String actorUsername;
    private UUID actorEmployeeId;
    private String reason;
    private UUID approvalInstanceId;
    private String sourceDocumentType;
    private UUID sourceDocumentId;
    private String sourceDocumentName;
    private Long aggregateVersion;
}

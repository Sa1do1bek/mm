package com.enterprise.mm.payload;

import java.util.UUID;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockExecutionResponse extends BaseRecordDto {
    private UUID caseId;
    private UUID decisionId;
    private String executionStatus;
    private UUID stockMovementId;
    private UUID stockMovementDocumentId;
    private UUID valuationDocumentId;
    private UUID integrationEventId;
    private String idempotencyKey;
    private Integer attemptNo;
    private String reconciliationStatus;
    private String failureSummary;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<NonMovingStockExecutionOutputResponse> outputs = new ArrayList<>();
}

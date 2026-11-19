package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManagementOpportunityCalculationRunResponse extends BaseRecordDto {
    private LocalDate asOfDate;
    private String targetCurrencyCode;
    private String requestedTypes;
    private int candidateCount;
    private int skippedCount;
    private String skipSummary;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String failureSummary;
}

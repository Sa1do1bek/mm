package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "management_opportunity_runs")
public class ManagementOpportunityCalculationRun extends BusinessEntity {
    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "target_currency_code", nullable = false, length = 3)
    private String targetCurrencyCode;

    @Column(name = "requested_types", nullable = false, columnDefinition = "text")
    private String requestedTypes;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "skip_summary", columnDefinition = "text")
    private String skipSummary;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failure_summary", columnDefinition = "text")
    private String failureSummary;
}

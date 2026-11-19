package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "non_moving_stock_executions")
public class NonMovingStockExecution extends BusinessEntity {
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "decision_id", nullable = false)
    private UUID decisionId;
    @Column(length = 50, nullable = false)
    private String executionStatus;
    @Column(name = "stock_movement_id")
    private UUID stockMovementId;
    @Column(name = "valuation_document_id")
    private UUID valuationDocumentId;
    @Column(name = "integration_event_id")
    private UUID integrationEventId;
    @Column(name = "idempotency_key", length = 120)
    private String idempotencyKey;
    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo = 1;
    @Column(name = "reconciliation_status", length = 50)
    private String reconciliationStatus;
    @Column(name = "failure_summary", columnDefinition = "text")
    private String failureSummary;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}

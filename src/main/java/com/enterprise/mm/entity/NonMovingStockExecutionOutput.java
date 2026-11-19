package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "non_moving_stock_execution_outputs")
public class NonMovingStockExecutionOutput extends BusinessEntity {
    @Column(name = "execution_id", nullable = false)
    private UUID executionId;
    @Column(name = "decision_line_id")
    private UUID decisionLineId;
    @Column(name = "case_line_id")
    private UUID caseLineId;
    @Column(name = "output_type", nullable = false, length = 50)
    private String outputType;
    @Column(name = "document_type", length = 80)
    private String documentType;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "document_number", length = 120)
    private String documentNumber;
    @Column(name = "output_status", nullable = false, length = 50)
    private String outputStatus;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity;
    @Column(precision = 19, scale = 4)
    private BigDecimal amount;
    @Column(name = "currency_code", length = 3)
    private String currencyCode;
    @Column(name = "error_code", length = 80)
    private String errorCode;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "non_moving_stock_decision_lines")
public class NonMovingStockDecisionLine extends BusinessEntity {
    @Column(name = "decision_id", nullable = false)
    private UUID decisionId;
    @Column(name = "case_line_id", nullable = false)
    private UUID caseLineId;
    @Column(name = "decision_type", nullable = false, length = 50)
    private String decisionType;
    @Column(name = "approved_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal approvedQuantity;
    @Column(name = "target_warehouse_id")
    private UUID targetWarehouseId;
    @Column(name = "expected_recovery_amount", precision = 19, scale = 4)
    private BigDecimal expectedRecoveryAmount;
    @Column(name = "financial_amount", precision = 19, scale = 4)
    private BigDecimal financialAmount;
    @Column(name = "currency_code", length = 3)
    private String currencyCode;
    @Column(name = "execution_due_date")
    private LocalDate executionDueDate;
    @Column(name = "controlled_reason", columnDefinition = "text")
    private String controlledReason;
    @Column(columnDefinition = "text")
    private String comment;
    @Column(name = "line_order", nullable = false)
    private Integer lineOrder = 0;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "non_moving_stock_decisions")
public class NonMovingStockDecision extends BusinessEntity {
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(length = 50, nullable = false)
    private String decisionType;
    @Column(precision = 22, scale = 7)
    private BigDecimal approvedQuantity = BigDecimal.ZERO;
    @Column(name = "target_warehouse_id")
    private UUID targetWarehouseId;
    @Column(precision = 19, scale = 4)
    private BigDecimal salePrice;
    @Column(precision = 19, scale = 4)
    private BigDecimal impairmentReserveAmount = BigDecimal.ZERO;
    @Column(columnDefinition = "text")
    private String writeOffReason;
    @Column(columnDefinition = "text")
    private String comment;
    private LocalDate nextReviewDate;
    @Column(name = "expected_recovery_amount", precision = 19, scale = 4)
    private BigDecimal expectedRecoveryAmount;
    @Column(name = "expected_recovery_currency_code", length = 3)
    private String expectedRecoveryCurrencyCode;
    @Column(name = "recovery_basis_reference", length = 160)
    private String recoveryBasisReference;
    @Column(name = "recovery_allocation_basis", length = 40)
    private String recoveryAllocationBasis;
    @Column(name = "decision_version", nullable = false)
    private Integer decisionVersion = 1;
    @Column(nullable = false)
    private boolean immutable;
    private LocalDateTime submittedAt;
    @Column(length = 120)
    private String submittedBy;
    @Column(name = "supersedes_decision_id")
    private UUID supersedesDecisionId;
}

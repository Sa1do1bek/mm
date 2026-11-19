package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ManagementOpportunityConfidence;
import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "management_opportunities")
public class ManagementOpportunity extends BusinessEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "opportunity_type", nullable = false, length = 60)
    private ManagementOpportunityType opportunityType;

    @Column(name = "calculation_run_id", nullable = false)
    private UUID calculationRunId;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "source_aggregate_type", nullable = false, length = 80)
    private String sourceAggregateType;

    @Column(name = "source_aggregate_id", nullable = false)
    private UUID sourceAggregateId;

    @Column(name = "source_line_id")
    private UUID sourceLineId;

    @Column(name = "material_id")
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "supplier_id")
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "warehouse_id")
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "source_currency_code", nullable = false, length = 3)
    private String sourceCurrencyCode;

    @Column(name = "normalized_currency_code", length = 3)
    private String normalizedCurrencyCode;

    @Column(name = "source_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal sourceAmount = BigDecimal.ZERO;

    @Column(name = "normalized_amount", precision = 19, scale = 4)
    private BigDecimal normalizedAmount;

    @Column(name = "baseline_amount", precision = 19, scale = 4)
    private BigDecimal baselineAmount;

    @Column(name = "actual_amount", precision = 19, scale = 4)
    private BigDecimal actualAmount;

    @Column(precision = 22, scale = 7)
    private BigDecimal quantity;

    @Column(name = "exchange_rate", precision = 19, scale = 8)
    private BigDecimal exchangeRate;

    @Column(name = "exchange_rate_id")
    private UUID exchangeRateId;

    @Column(name = "exchange_rate_date")
    private LocalDate exchangeRateDate;

    @Column(name = "exchange_rate_source", length = 120)
    private String exchangeRateSource;

    @Column(name = "normalization_quality", length = 50)
    private String normalizationQuality;

    @Column(name = "formula_code", nullable = false, length = 120)
    private String formulaCode;

    @Column(name = "formula_snapshot", nullable = false, columnDefinition = "text")
    private String formulaSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ManagementOpportunityConfidence confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_completeness", nullable = false, length = 30)
    private ManagementOpportunityDataCompleteness dataCompleteness;

    @Column(name = "assigned_role", length = 80)
    private String assignedRole;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by", length = 120)
    private String submittedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 120)
    private String reviewedBy;

    @Column(name = "review_comment", columnDefinition = "text")
    private String reviewComment;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by", length = 120)
    private String approvedBy;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;
}

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
@Table(name = "procurement_campaigns")
public class ProcurementCampaign extends BusinessEntity {
    @Column(name = "campaign_number", length = 120)
    private String campaignNumber;

    @Column(name = "procurement_plan_id")
    private UUID procurementPlanId;

    @Column(name = "category_strategy_id")
    private UUID categoryStrategyId;

    @Column(name = "material_group_id")
    private UUID materialGroupId;

    @Column(name = "rfq_id")
    private UUID rfqId;

    @Column(name = "price_discovery_rfq_id")
    private UUID priceDiscoveryRfqId;

    @Column(name = "price_basis_calculation_id")
    private UUID priceBasisCalculationId;

    @Column(name = "purchase_request_id")
    private UUID purchaseRequestId;

    @Column(name = "method_decision_id")
    private UUID methodDecisionId;

    @Column(name = "procurement_procedure_id")
    private UUID procurementProcedureId;

    @Column(name = "campaign_type", nullable = false, length = 60)
    private String campaignType = "ANNUAL_TENDER";

    @Column(name = "procedure_type", nullable = false, length = 60)
    private String procedureType = "REQUEST_FOR_QUOTATION";

    @Column(name = "target_supplier_count", nullable = false)
    private Integer targetSupplierCount = 3;

    @Column(name = "planned_spend_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal plannedSpendAmount = BigDecimal.ZERO;

    @Column(name = "approved_spend_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal approvedSpendAmount = BigDecimal.ZERO;

    @Column(name = "savings_target_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal savingsTargetAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "planned_start_date")
    private LocalDate plannedStartDate;

    @Column(name = "planned_end_date")
    private LocalDate plannedEndDate;

    @Column(name = "launched_at")
    private LocalDateTime launchedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}

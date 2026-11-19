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
@Table(name = "procurement_procedures")
public class ProcurementProcedure extends BusinessEntity {
    @Column(name = "procedure_number", nullable = false, length = 120)
    private String procedureNumber;

    @Column(name = "method_decision_id", nullable = false)
    private UUID methodDecisionId;

    @Column(name = "procurement_plan_id")
    private UUID procurementPlanId;

    @Column(name = "purchase_request_id")
    private UUID purchaseRequestId;

    @Column(name = "method", nullable = false, length = 60, updatable = false)
    private String method;

    @Column(name = "execution_path", nullable = false, length = 60)
    private String executionPath;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "rfq_id")
    private UUID rfqId;

    @Column(name = "direct_award_id")
    private UUID directAwardId;

    @Column(name = "awarded_supplier_id")
    private UUID awardedSupplierId;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "contract_id")
    private UUID contractId;

    @Column(name = "award_reference", length = 160)
    private String awardReference;

    @Column(name = "price_discovery_rfq_id")
    private UUID priceDiscoveryRfqId;

    @Column(name = "requires_price_discovery", nullable = false)
    private boolean requiresPriceDiscovery;

    @Column(name = "finalized_amount", precision = 19, scale = 4)
    private BigDecimal finalizedAmount;

    @Column(name = "finalized_currency_code", length = 3)
    private String finalizedCurrencyCode;

    @Column(name = "price_basis_calculation_id")
    private UUID priceBasisCalculationId;

    @Column(name = "launched_at")
    private LocalDateTime launchedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** Capability evidence copied from the approved method decision for grandfathering. */
    @Column(name = "capability_state_snapshot", length = 20)
    private String capabilityStateSnapshot;

    @Column(name = "capability_version_snapshot")
    private Integer capabilityVersionSnapshot;

    @Column(name = "cancellation_reason", columnDefinition = "text")
    private String cancellationReason;
}

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
@Table(name = "procurement_method_decisions")
public class ProcurementMethodDecision extends BusinessEntity {
    @Column(name = "decision_number", nullable = false, length = 120)
    private String decisionNumber;

    @Column(name = "purchase_request_id")
    private UUID purchaseRequestId;

    @Column(name = "selected_method", nullable = false, length = 60)
    private String selectedMethod;

    @Column(name = "justification", columnDefinition = "text")
    private String justification;

    @Column(name = "estimated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal estimatedAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "potential_supplier_count", nullable = false)
    private Integer potentialSupplierCount = 0;

    @Column(name = "commission_id")
    private UUID commissionId;

    @Column(name = "market_research_attachment_id")
    private UUID marketResearchAttachmentId;

    @Column(name = "technical_specification_attachment_id")
    private UUID technicalSpecificationAttachmentId;

    @Column(name = "exception_reason", columnDefinition = "text")
    private String exceptionReason;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by", length = 120)
    private String approvedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "rejected_by", length = 120)
    private String rejectedBy;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    /** Immutable capability evidence captured when this decision is approved. */
    @Column(name = "capability_state_snapshot", length = 20)
    private String capabilityStateSnapshot;

    @Column(name = "capability_version_snapshot")
    private Integer capabilityVersionSnapshot;
}

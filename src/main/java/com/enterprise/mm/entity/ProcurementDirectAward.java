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
@Table(name = "procurement_direct_awards")
public class ProcurementDirectAward extends BusinessEntity {
    @Column(name = "procurement_procedure_id", nullable = false)
    private UUID procurementProcedureId;

    @Column(name = "method_decision_id", nullable = false)
    private UUID methodDecisionId;

    @Column(name = "purchase_request_id")
    private UUID purchaseRequestId;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "method", nullable = false, length = 60, updatable = false)
    private String method;

    @Column(name = "award_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal awardAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "justification", nullable = false, columnDefinition = "text")
    private String justification;

    @Column(name = "award_basis_type", nullable = false, length = 60)
    private String awardBasisType;

    @Column(name = "award_basis", columnDefinition = "text")
    private String awardBasis;

    @Column(name = "evidence_reference", length = 500)
    private String evidenceReference;

    @Column(name = "market_price_amount", precision = 19, scale = 4)
    private BigDecimal marketPriceAmount;

    @Column(name = "market_price_evidence_reference", length = 500)
    private String marketPriceEvidenceReference;

    @Column(name = "negotiation_summary", columnDefinition = "text")
    private String negotiationSummary;

    @Column(name = "conflict_of_interest_declaration", columnDefinition = "text")
    private String conflictOfInterestDeclaration;

    @Column(name = "conflict_of_interest_confirmed", nullable = false)
    private boolean conflictOfInterestConfirmed;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by", length = 120)
    private String approvedBy;

    @Column(name = "approved_supplier_id")
    private UUID approvedSupplierId;

    @Column(name = "approved_award_amount", precision = 19, scale = 4)
    private BigDecimal approvedAwardAmount;

    @Column(name = "approved_method", length = 60)
    private String approvedMethod;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;
}

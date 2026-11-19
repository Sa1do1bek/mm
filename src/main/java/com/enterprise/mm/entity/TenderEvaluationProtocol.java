package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tender_evaluation_protocols")
public class TenderEvaluationProtocol extends BusinessEntity {
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;

    @Column(name = "protocol_number", nullable = false, length = 80)
    private String protocolNumber;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    @Column(name = "selection_rule", nullable = false, length = 50)
    private String selectionRule;

    @Column(name = "recommended_quotation_id", nullable = false)
    private UUID recommendedQuotationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommended_quotation_id", insertable = false, updatable = false)
    private SupplierQuotation recommendedQuotation;

    @Column(name = "recommended_supplier_id", nullable = false)
    private UUID recommendedSupplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommended_supplier_id", insertable = false, updatable = false)
    private Supplier recommendedSupplier;

    @Column(name = "recommendation_reason", columnDefinition = "text")
    private String recommendationReason;

    @Column(name = "scoring_summary", columnDefinition = "text")
    private String scoringSummary;

    @Column(name = "conflict_summary", columnDefinition = "text")
    private String conflictSummary;

    @Column(name = "approved_by", length = 120)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tender_bid_opening_lines")
public class TenderBidOpeningLine extends BusinessEntity {
    @Column(name = "protocol_id", nullable = false)
    private UUID protocolId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_id", insertable = false, updatable = false)
    private TenderBidOpeningProtocol protocol;

    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;

    @Column(name = "quotation_id", nullable = false)
    private UUID quotationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", insertable = false, updatable = false)
    private SupplierQuotation quotation;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "accepted_for_evaluation", nullable = false)
    private boolean acceptedForEvaluation;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
}

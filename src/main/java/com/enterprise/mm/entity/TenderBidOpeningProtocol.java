package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tender_bid_opening_protocols")
public class TenderBidOpeningProtocol extends BusinessEntity {
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;

    @Column(name = "protocol_number", nullable = false, length = 80)
    private String protocolNumber;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "opened_by", length = 120)
    private String openedBy;

    @Column(name = "quorum_required", nullable = false)
    private Integer quorumRequired = 1;

    @Column(name = "quorum_present", nullable = false)
    private Integer quorumPresent = 0;

    @Column(name = "quorum_confirmed", nullable = false)
    private boolean quorumConfirmed;

    @Column(name = "quotation_count", nullable = false)
    private Integer quotationCount = 0;

    @Column(name = "accepted_quotation_count", nullable = false)
    private Integer acceptedQuotationCount = 0;

    @Column(name = "rejected_quotation_count", nullable = false)
    private Integer rejectedQuotationCount = 0;

    @Column(columnDefinition = "text")
    private String minutes;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        rfqId = rfq == null ? rfqId : rfq.getId();
    }
}

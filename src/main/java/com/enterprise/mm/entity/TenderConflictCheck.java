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
@Table(name = "tender_conflict_checks")
public class TenderConflictCheck extends BusinessEntity {
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;

    @Column(name = "quotation_id")
    private UUID quotationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", insertable = false, updatable = false)
    private SupplierQuotation quotation;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "commission_member_id")
    private UUID commissionMemberId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commission_member_id", insertable = false, updatable = false)
    private TenderCommissionMember commissionMember;

    @Column(name = "related_party_id")
    private UUID relatedPartyId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_party_id", insertable = false, updatable = false)
    private SupplierRelatedParty relatedParty;

    @Column(name = "conflict_found", nullable = false)
    private boolean conflictFound;

    @Column(name = "conflict_type", length = 80)
    private String conflictType;

    @Column(length = 40)
    private String severity = "LOW";

    @Column(columnDefinition = "text")
    private String details;

    @Column(name = "mitigation_action", columnDefinition = "text")
    private String mitigationAction;

    @Column(name = "resolved_flag", nullable = false)
    private boolean resolved;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        rfqId = rfq == null ? rfqId : rfq.getId();
        quotationId = quotation == null ? quotationId : quotation.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
        commissionMemberId = commissionMember == null ? commissionMemberId : commissionMember.getId();
        relatedPartyId = relatedParty == null ? relatedPartyId : relatedParty.getId();
    }
}

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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tender_commissions")
public class TenderCommission extends BusinessEntity {
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;

    @Column(name = "commission_id", nullable = false)
    private UUID commissionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commission_id", insertable = false, updatable = false)
    private Commission commission;

    @Column(name = "quorum_required")
    private Integer quorumRequired;

    @Column(name = "active_flag", nullable = false)
    private boolean active = true;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        rfqId = rfq == null ? rfqId : rfq.getId();
        commissionId = commission == null ? commissionId : commission.getId();
    }
}

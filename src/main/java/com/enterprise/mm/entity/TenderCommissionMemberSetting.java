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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tender_commission_member_settings")
public class TenderCommissionMemberSetting extends BusinessEntity {
    @Column(name = "tender_commission_id", nullable = false)
    private UUID tenderCommissionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tender_commission_id", insertable = false, updatable = false)
    private TenderCommission tenderCommission;

    @Column(name = "commission_member_id", nullable = false)
    private UUID commissionMemberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commission_member_id", insertable = false, updatable = false)
    private CommissionMember commissionMember;

    @Column(name = "voting_weight", nullable = false, precision = 7, scale = 2)
    private BigDecimal votingWeight = BigDecimal.ONE;

    @Column(name = "conflict_declaration_signed", nullable = false)
    private boolean conflictDeclarationSigned;

    @Column(name = "declared_at")
    private LocalDateTime declaredAt;

    @Column(name = "active_flag", nullable = false)
    private boolean active = true;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        tenderCommissionId = tenderCommission == null ? tenderCommissionId : tenderCommission.getId();
        commissionMemberId = commissionMember == null ? commissionMemberId : commissionMember.getId();
    }
}

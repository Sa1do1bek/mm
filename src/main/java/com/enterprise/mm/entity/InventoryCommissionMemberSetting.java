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
@Table(name = "inventory_commission_member_settings")
public class InventoryCommissionMemberSetting extends BusinessEntity {
    @Column(name = "commission_id", nullable = false)
    private UUID commissionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commission_id", insertable = false, updatable = false)
    private Commission commission;

    @Column(name = "commission_member_id", nullable = false)
    private UUID commissionMemberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commission_member_id", insertable = false, updatable = false)
    private CommissionMember commissionMember;

    @Column(name = "show_system_quantity_to_counter", nullable = false)
    private boolean showSystemQuantityToCounter = true;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        commissionId = commission == null ? commissionId : commission.getId();
        commissionMemberId = commissionMember == null ? commissionMemberId : commissionMember.getId();
    }
}

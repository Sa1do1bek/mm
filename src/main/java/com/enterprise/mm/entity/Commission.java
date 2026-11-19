package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.CommissionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "commissions")
public class Commission extends BusinessEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "commission_type", nullable = false, length = 40)
    private CommissionType type = CommissionType.INVENTORY;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "active_flag", nullable = false)
    private boolean active = true;

}

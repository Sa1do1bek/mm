package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ManagementOpportunityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "management_opportunity_policies")
public class ManagementOpportunityPolicy extends BusinessEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "opportunity_type", nullable = false, length = 60)
    private ManagementOpportunityType opportunityType;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "warehouse_id")
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "material_group_id")
    private UUID materialGroupId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_group_id", insertable = false, updatable = false)
    private MaterialGroup materialGroup;

    @Column(name = "supplier_id")
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "annual_carrying_cost_rate", precision = 9, scale = 6)
    private BigDecimal annualCarryingCostRate;

    @Column(name = "daily_impact_rate", precision = 19, scale = 4)
    private BigDecimal dailyImpactRate;

    @Column(name = "attributable_share", precision = 9, scale = 6)
    private BigDecimal attributableShare;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "activated_by", length = 120)
    private String activatedBy;
}

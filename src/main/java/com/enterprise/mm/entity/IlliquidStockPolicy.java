package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "illiquid_stock_policies")
public class IlliquidStockPolicy extends BusinessEntity {
    @Column(name = "legal_entity_code", length = 80)
    private String legalEntityCode;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "material_category", length = 120)
    private String materialCategory;

    @Column(name = "material_type", length = 120)
    private String materialType;

    @Column(name = "aging_days", nullable = false)
    private Integer agingDays;

    @Column(name = "excess_days")
    private Integer excessDays;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "activated_by", length = 120)
    private String activatedBy;
}

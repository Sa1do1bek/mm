package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "material_market_prices")
public class MaterialMarketPrice extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Column(name = "warehouse_id")
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;
    @Column(name = "market_unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal marketUnitPrice = BigDecimal.ZERO;
    @Column(name = "net_realizable_unit_price", precision = 19, scale = 4)
    private BigDecimal netRealizableUnitPrice = BigDecimal.ZERO;
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;
    @Column(name = "source_type", length = 80)
    private String sourceType;
    @Column(name = "source_reference", length = 160)
    private String sourceReference;
    @Column(name = "approved_by", length = 120)
    private String approvedBy;
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "inventory_fx_differences")
public class InventoryFxDifference extends BusinessEntity {
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "document_date", nullable = false)
    private LocalDate documentDate;

    @Column(name = "reference_type", length = 80)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "material_id")
    private UUID materialId;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "document_currency_code", nullable = false, length = 3)
    private String documentCurrencyCode = "USD";

    @Column(name = "base_currency_code", nullable = false, length = 3)
    private String baseCurrencyCode = "UZS";

    @Column(name = "foreign_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal foreignAmount = BigDecimal.ZERO;

    @Column(name = "document_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal documentRate = BigDecimal.ONE;

    @Column(name = "closing_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal closingRate = BigDecimal.ONE;

    @Column(name = "base_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal baseAmount = BigDecimal.ZERO;

    @Column(name = "revalued_base_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal revaluedBaseAmount = BigDecimal.ZERO;

    @Column(name = "difference_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal differenceAmount = BigDecimal.ZERO;

    @Column(name = "difference_type", nullable = false, length = 20)
    private String differenceType = "NONE";

    @Column(name = "posted_at")
    private LocalDateTime postedAt;
}
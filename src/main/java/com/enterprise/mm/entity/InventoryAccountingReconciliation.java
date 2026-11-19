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
@Table(name = "inventory_accounting_reconciliations")
public class InventoryAccountingReconciliation extends BusinessEntity {
    @Column(name = "transit_stock_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal transitStockValue = BigDecimal.ZERO;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "accounting_system", nullable = false, length = 80)
    private String accountingSystem = "FI";

    @Column(name = "external_reference", length = 160)
    private String externalReference;

    @Column(name = "inventory_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal inventoryValue = BigDecimal.ZERO;

    @Column(name = "accounting_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal accountingValue = BigDecimal.ZERO;

    @Column(name = "difference_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal differenceAmount = BigDecimal.ZERO;

    @Column(name = "tolerance_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal toleranceAmount = BigDecimal.ZERO;

    @Column(name = "difference_status", nullable = false, length = 30)
    private String differenceStatus = "NOT_CHECKED";

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;
}
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
@Table(name = "inventory_period_closes")
public class InventoryPeriodClose extends BusinessEntity {
    @Column(name = "transit_stock_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal transitStockValue = BigDecimal.ZERO;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "total_stock_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalStockValue = BigDecimal.ZERO;

    @Column(name = "landed_cost_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal landedCostAmount = BigDecimal.ZERO;

    @Column(name = "fx_difference_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal fxDifferenceAmount = BigDecimal.ZERO;

    @Column(name = "movement_count", nullable = false)
    private long movementCount;

    @Column(name = "open_movement_count", nullable = false)
    private long openMovementCount;

    @Column(name = "valuation_count", nullable = false)
    private long valuationCount;

    @Column(name = "accounting_reconciliation_id")
    private UUID accountingReconciliationId;

    @Column(name = "accounting_reconciliation_status", length = 30)
    private String accountingReconciliationStatus;

    @Column(name = "accounting_difference_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal accountingDifferenceAmount = BigDecimal.ZERO;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
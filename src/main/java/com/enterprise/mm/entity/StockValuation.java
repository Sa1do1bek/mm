package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ValuationMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_valuations")
public class StockValuation extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ValuationMethod valuationMethod;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal totalValue = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal impairmentReserve = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal revaluationAmount = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal carryingValue = BigDecimal.ZERO;
    @Column(length = 80)
    private String valuationBasis;
    private LocalDateTime calculatedAt;
}
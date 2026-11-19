package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ValuationMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "stock_valuation_documents")
public class StockValuationDocument extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Column(length = 40, nullable = false)
    private String documentType = "REVALUATION";
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ValuationMethod valuationMethod;
    private LocalDate documentDate;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal oldUnitCost = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal newUnitCost = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal reserveAmount = BigDecimal.ZERO;
    @Column(columnDefinition = "text")
    private String reason;
    private LocalDateTime postedAt;
}
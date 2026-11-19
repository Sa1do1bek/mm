package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tender_price_calculation_quotations")
public class TenderPriceCalculationQuotation extends BusinessEntity {
    @Column(name = "calculation_id", nullable = false)
    private UUID calculationId;

    @Column(name = "quotation_id", nullable = false)
    private UUID quotationId;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";
}

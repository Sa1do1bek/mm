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
@Table(name = "landed_cost_allocations")
public class LandedCostAllocation extends BusinessEntity {
    @Column(name = "import_delivery_id", nullable = false)
    private UUID importDeliveryId;

    @Column(name = "allocation_method", nullable = false, length = 40)
    private String allocationMethod = "VALUE";

    @Column(name = "document_date", nullable = false)
    private LocalDate documentDate;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "base_currency_code", nullable = false, length = 3)
    private String baseCurrencyCode = "UZS";

    @Column(name = "exchange_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal exchangeRate = BigDecimal.ONE;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "base_total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal baseTotalAmount = BigDecimal.ZERO;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;
}
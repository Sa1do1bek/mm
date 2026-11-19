package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tender_price_calculations")
public class TenderPriceCalculation extends BusinessEntity {
    @Column(name = "procurement_procedure_id", nullable = false)
    private UUID procurementProcedureId;

    @Column(name = "price_discovery_rfq_id", nullable = false)
    private UUID priceDiscoveryRfqId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "quotation_count", nullable = false)
    private Integer quotationCount = 0;

    @Column(name = "calculated_by", length = 120)
    private String calculatedBy;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;
}

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

/** Immutable posting header for one idempotent warehouse receipt command. */
@Getter
@Setter
@Entity
@Table(name = "goods_receipts")
public class GoodsReceipt extends BusinessEntity {
    @Column(name = "contract_id", updatable = false)
    private UUID contractId;

    @Column(name = "purchase_order_id", nullable = false, updatable = false)
    private UUID purchaseOrderId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "asn_id", updatable = false)
    private UUID asnId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "idempotency_key", length = 160, updatable = false)
    private String idempotencyKey;

    @Column(name = "currency_code", length = 12, nullable = false, updatable = false)
    private String currencyCode;

    @Column(name = "exchange_rate", precision = 19, scale = 8, nullable = false, updatable = false)
    private BigDecimal exchangeRate = BigDecimal.ONE;

    @Column(name = "base_currency_code", length = 12, nullable = false, updatable = false)
    private String baseCurrencyCode = "UZS";

    @Column(name = "accepted_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;

    @Column(name = "rejected_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;

    @Column(name = "damaged_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal damagedQuantity = BigDecimal.ZERO;

    @Column(name = "excess_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal excessQuantity = BigDecimal.ZERO;

    @Column(name = "shortage_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal shortageQuantity = BigDecimal.ZERO;

    @Column(name = "accepted_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal acceptedAmount = BigDecimal.ZERO;

    @Column(name = "base_accepted_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal baseAcceptedAmount = BigDecimal.ZERO;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 120, nullable = false, updatable = false)
    private String postedBy;
}

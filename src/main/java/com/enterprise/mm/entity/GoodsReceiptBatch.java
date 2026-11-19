package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "goods_receipt_batches")
public class GoodsReceiptBatch extends BusinessEntity {
    @Column(name = "goods_receipt_line_id", nullable = false, updatable = false)
    private UUID goodsReceiptLineId;
    @Column(name = "stock_batch_id", updatable = false)
    private UUID stockBatchId;
    @Column(name = "stock_movement_id", updatable = false)
    private UUID stockMovementId;
    @Column(name = "manufacturer_batch_number", length = 160, updatable = false)
    private String manufacturerBatchNumber;
    @Column(name = "manufactured_on", updatable = false)
    private LocalDate manufacturedOn;
    @Column(name = "expires_on", updatable = false)
    private LocalDate expiresOn;
    @Column(name = "accepted_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;
    @Column(name = "rejected_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;
    @Column(name = "damaged_quantity", precision = 22, scale = 7, nullable = false, updatable = false)
    private BigDecimal damagedQuantity = BigDecimal.ZERO;
}

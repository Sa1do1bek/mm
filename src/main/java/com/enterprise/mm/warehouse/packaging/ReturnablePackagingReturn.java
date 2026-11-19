package com.enterprise.mm.warehouse.packaging;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity
@Table(name = "returnable_packaging_returns")
public class ReturnablePackagingReturn extends BusinessEntity {
    @Column(name = "lot_id", nullable = false) private UUID lotId;
    @Column(name = "supplier_id", nullable = false) private UUID supplierId;
    @Column(nullable = false, precision = 22, scale = 7) private BigDecimal quantity;
    @Column(name = "confirmed_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal confirmedQuantity = BigDecimal.ZERO;
    @Column(name = "recalled_quantity", nullable = false, precision = 22, scale = 7) private BigDecimal recalledQuantity = BigDecimal.ZERO;
    @Column(name = "dispatch_stock_movement_id") private UUID dispatchStockMovementId;
    @Column(name = "dispatch_posting_batch_id") private UUID dispatchPostingBatchId;
    @Column(name = "recall_stock_movement_id") private UUID recallStockMovementId;
    @Column(name = "recall_posting_batch_id") private UUID recallPostingBatchId;
    @Column(name = "document_number", length = 120) private String documentNumber;
    @Column(columnDefinition = "text") private String reason;
    @Column(name = "recall_reason", columnDefinition = "text") private String recallReason;
    @Column(name = "approved_at") private LocalDateTime approvedAt;
    @Column(name = "dispatched_at") private LocalDateTime dispatchedAt;
    @Column(name = "confirmed_at") private LocalDateTime confirmedAt;
    @Column(name = "recalled_at") private LocalDateTime recalledAt;
}

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
@Table(name = "stock_cost_movements")
public class StockCostMovement extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "stock_movement_id")
    private UUID stockMovementId;

    @Column(name = "cost_layer_id")
    private UUID costLayerId;

    @Column(name = "movement_type", nullable = false, length = 30)
    private String movementType;

    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "total_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "cost_center_id")
    private UUID costCenterId;

    @Column(name = "source_document_type", length = 50)
    private String sourceDocumentType;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "is_reversal", nullable = false)
    private boolean reversal;

    @Column(name = "reversal_of_id")
    private UUID reversalOfId;

    @Column(name = "reversal_reason", columnDefinition = "text")
    private String reversalReason;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversed_by", length = 120)
    private String reversedBy;

    @Column(name = "transfer_transaction_id")
    private UUID transferTransactionId;
}

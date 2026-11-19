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
@Table(name = "inventory_cost_layers")
public class InventoryCostLayer extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "stock_batch_id")
    private UUID stockBatchId;

    @Column(name = "source_movement_id", nullable = false)
    private UUID sourceMovementId;

    @Column(name = "source_document_type", length = 50)
    private String sourceDocumentType;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "parent_cost_layer_id")
    private UUID parentCostLayerId;

    @Column(name = "transfer_transaction_id")
    private UUID transferTransactionId;

    @Column(name = "source_warehouse_id")
    private UUID sourceWarehouseId;

    @Column(name = "target_warehouse_id")
    private UUID targetWarehouseId;

    @Column(name = "source_stock_movement_id")
    private UUID sourceStockMovementId;

    @Column(name = "target_stock_movement_id")
    private UUID targetStockMovementId;

    @Column(name = "layer_type", nullable = false, length = 30)
    private String layerType;

    @Column(name = "received_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Column(name = "remaining_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal remainingQuantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "total_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "authoritative_value", nullable = false)
    private boolean authoritativeValue;

    @Column(name = "currency_code", nullable = false, length = 10)
    private String currencyCode = "UZS";

    @Column(name = "exchange_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal exchangeRate = BigDecimal.ONE;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Immutable
@Table(name = "transfer_shipment_allocations")
public class TransferShipmentAllocation extends BusinessEntity {
    @Column(name = "source_stock_movement_id", updatable = false)
    private UUID sourceStockMovementId;

    @Column(name = "allocation_sequence", nullable = false, updatable = false)
    private int allocationSequence;

    @Column(name = "shipment_id", updatable = false, nullable = false)
    private UUID shipmentId;

    @Column(name = "transfer_document_id", updatable = false, nullable = false)
    private UUID transferDocumentId;

    @Column(name = "transfer_line_id", updatable = false, nullable = false)
    private UUID transferLineId;

    @Column(name = "material_id", updatable = false, nullable = false)
    private UUID materialId;

    @Column(name = "unit_of_measure_id", updatable = false)
    private UUID unitOfMeasureId;

    @Column(name = "unit_code", updatable = false, nullable = false, length = 80)
    private String unitCode;

    @Column(name = "quantity_scale", updatable = false, nullable = false)
    private int quantityScale;

    @Column(name = "source_warehouse_id", updatable = false, nullable = false)
    private UUID sourceWarehouseId;

    @Column(name = "source_storage_location_id", updatable = false)
    private UUID sourceStorageLocationId;

    @Column(name = "source_storage_bin_id", updatable = false)
    private UUID sourceStorageBinId;

    @Column(name = "source_stock_batch_id", updatable = false)
    private UUID sourceStockBatchId;

    @Column(name = "batch_number", updatable = false, length = 120)
    private String batchNumber;

    @Column(name = "serial_number", updatable = false, length = 160)
    private String serialNumber;

    @Column(name = "expiry_date", updatable = false)
    private LocalDate expiryDate;

    @Column(name = "dispatched_quantity", updatable = false, nullable = false, precision = 22, scale = 7)
    private BigDecimal dispatchedQuantity;

    @Column(name = "original_value", updatable = false, nullable = false, precision = 19, scale = 4)
    private BigDecimal originalValue;

    @Column(name = "book_value", updatable = false, nullable = false, precision = 19, scale = 4)
    private BigDecimal bookValue;

    @Column(name = "currency_code", updatable = false, nullable = false, length = 10)
    private String currencyCode;

    @Column(name = "book_currency_code", updatable = false, nullable = false, length = 10)
    private String bookCurrencyCode;

    @Column(name = "exchange_rate", updatable = false, nullable = false, precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "source_movement_allocation_id", updatable = false)
    private UUID sourceMovementAllocationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consumed_cost_layer_ids", updatable = false, nullable = false, columnDefinition = "jsonb")
    private List<UUID> consumedCostLayerIds = List.of();

}

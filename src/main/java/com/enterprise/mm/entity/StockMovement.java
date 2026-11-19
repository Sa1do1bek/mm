package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "stock_movements")
public class StockMovement extends BusinessEntity {
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;
    @Column(name = "target_warehouse_id")
    private UUID targetWarehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_warehouse_id", insertable = false, updatable = false)
    private Warehouse targetWarehouse;
    @Column(name = "storage_location_id")
    private UUID storageLocationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id", insertable = false, updatable = false)
    private StorageLocation storageLocation;
    @Column(name = "storage_bin_id")
    private UUID storageBinId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_bin_id", insertable = false, updatable = false)
    private StorageBin storageBin;
    @Column(name = "target_storage_location_id")
    private UUID targetStorageLocationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_storage_location_id", insertable = false, updatable = false)
    private StorageLocation targetStorageLocation;
    @Column(name = "target_storage_bin_id")
    private UUID targetStorageBinId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_storage_bin_id", insertable = false, updatable = false)
    private StorageBin targetStorageBin;
    @Column(name = "stock_batch_id")
    private UUID stockBatchId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_batch_id", insertable = false, updatable = false)
    private StockBatch stockBatch;
    @Column(name = "batch_number", length = 120)
    private String batchNumber;
    @Column(name = "target_stock_batch_id")
    private UUID targetStockBatchId;
    @Column(name = "batch_number_snapshot", length = 120)
    private String batchNumberSnapshot;
    @Column(name = "target_batch_number_snapshot", length = 120)
    private String targetBatchNumberSnapshot;
    @Column(name = "uom_id_snapshot")
    private UUID uomIdSnapshot;
    @Column(name = "uom_code_snapshot", length = 80)
    private String uomCodeSnapshot;
    @Column(name = "source_disposition_snapshot", length = 40)
    private String sourceDispositionSnapshot;
    @Column(name = "target_disposition_snapshot", length = 40)
    private String targetDispositionSnapshot;
    @Column(name = "source_inventory_item_id")
    private UUID sourceInventoryItemId;
    @Enumerated(EnumType.STRING)
    @Column(length = 40, nullable = false)
    private MovementType movementType;
    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal totalCost = BigDecimal.ZERO;
    @Column(name = "source_currency_code_snapshot", length = 12)
    private String sourceCurrencyCodeSnapshot;
    @Column(name = "source_exchange_rate_snapshot", precision = 19, scale = 8)
    private BigDecimal sourceExchangeRateSnapshot;
    @Column(name = "source_unit_price_snapshot", precision = 19, scale = 6, updatable = false)
    private BigDecimal sourceUnitPriceSnapshot;
    @Column(name = "account_code_snapshot", length = 40, updatable = false)
    private String accountCodeSnapshot;
    private LocalDate movementDate;
    private LocalDateTime requestDate;
    private LocalDateTime postedAt;
    @Column(length = 80)
    private String referenceType;
    @Column(name = "reference_id")
    private UUID referenceId;
    @Column(name = "contract_specification_id")
    private UUID contractSpecificationId;
    @Column(name = "reversal_of_id")
    private UUID reversalOfId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_id", insertable = false, updatable = false)
    private StockMovement reversalOf;
}

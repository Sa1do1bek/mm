package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockMovementResponse extends BaseRecordDto {
    private boolean historical;
    private Boolean stockEffects;
    private String sourceDirection;
    private String sourceQuantity;
    private String sourceBookValue;
    private UUID sourceRecordId;
    private String sourceDocumentKey;
    private UUID documentId;
    private StockMovementStatus stockMovementStatus;
    private UUID materialId;
    private String unitOfMeasureCode;
    private String unitOfMeasureShortName;
    private String materialCode;
    private String materialName;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private UUID targetWarehouseId;
    private String targetWarehouseCode;
    private String targetWarehouseName;
    private UUID storageLocationId;
    private String storageLocationCode;
    private String storageLocationName;
    private UUID storageBinId;
    private String storageBinCode;
    private String storageBinName;
    private UUID targetStorageLocationId;
    private String targetStorageLocationCode;
    private String targetStorageLocationName;
    private UUID targetStorageBinId;
    private String targetStorageBinCode;
    private String targetStorageBinName;
    private UUID stockBatchId;
    private String stockBatchCode;
    private String stockBatchName;
    private String batchNumber;
    private MovementType movementType;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private String accountCodeSnapshot;
    private LocalDate movementDate;
    private LocalDateTime requestDate;
    private LocalDateTime postedAt;
    private String actorName;
    private String referenceType;
    private UUID referenceId;
    private UUID contractSpecificationId;
    private UUID reversalOfId;
    private String reversalOfCode;
    private String reversalOfName;

    // Kirim qaysi skladdan yoki postavshikdan kelgani
    private UUID sourceWarehouseId;
    private String sourceWarehouseCode;
    private String sourceWarehouseName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String sourceLocation;
    private String sourceType;

    // Chiqim qayerga ketgani
    private UUID destinationWarehouseId;
    private String destinationWarehouseCode;
    private String destinationWarehouseName;
    private UUID departmentId;
    private String departmentCode;
    private String departmentName;
    private String destinationLocation;
    private String destinationType;

    // Unifikatsiyalangan kontragent / manzil
    private String counterparty;
}

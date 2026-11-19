package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockMovementCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private StockMovementStatus status;
    private String description;
    private UUID materialId;
    private UUID warehouseId;
    private UUID targetWarehouseId;
    private UUID storageLocationId;
    private UUID storageBinId;
    private UUID targetStorageLocationId;
    private UUID targetStorageBinId;
    private UUID stockBatchId;
    private String batchNumber;
    private MovementType movementType;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private LocalDate movementDate;
    private LocalDateTime requestDate;
    private LocalDateTime postedAt;
    private String referenceType;
    private UUID referenceId;
    private UUID reversalOfId;
    private List<StockMovementDocumentItemRequest> items;
}

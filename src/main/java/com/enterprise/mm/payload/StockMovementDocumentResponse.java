package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MovementType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockMovementDocumentResponse extends BaseRecordDto {
    private UUID warehouseId;
    private String warehouseName;
    private UUID targetWarehouseId;
    private String targetWarehouseName;
    private MovementType movementType;
    private LocalDate movementDate;
    private LocalDateTime postedAt;
    private String actorName;
    private String referenceType;
    private UUID referenceId;
    private UUID reversalOfId;
    private List<StockMovementResponse> items;
    private List<String> allowedActions;
}

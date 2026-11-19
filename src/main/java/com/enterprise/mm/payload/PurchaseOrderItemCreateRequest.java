package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseOrderItemCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID purchaseOrderId;
    private UUID purchaseRequestItemId;
    private String lineType;
    private UUID materialId;
    private MaterialType materialTypeSnapshot;
    private TrackingMode trackingModeSnapshot;
    private BatchTrackingPolicy batchTrackingSnapshot;
    private SerialTrackingPolicy serialTrackingSnapshot;
    private UUID serviceRequestId;
    private UUID serviceOrderId;
    private UUID serviceActId;
    private String serviceDescription;
    private String serviceCategory;
    private String unit;
    private String period;
    private String stage;
    private UUID budgetItemId;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal receivedQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal damagedQuantity;
    private String shortageReason;
    private String receiptBatchNumber;
    private UUID receiptWarehouseId;
    private UUID receiptStorageLocationId;
    private String qualityStatus;
    private String accreditationWarning;
}

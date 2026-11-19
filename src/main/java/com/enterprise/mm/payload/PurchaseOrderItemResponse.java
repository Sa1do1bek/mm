package com.enterprise.mm.payload;

import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseOrderItemResponse extends BaseRecordDto {
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private String lineType;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private MaterialType materialTypeSnapshot;
    private TrackingMode trackingModeSnapshot;
    private BatchTrackingPolicy batchTrackingSnapshot;
    private SerialTrackingPolicy serialTrackingSnapshot;
    private UUID serviceRequestId;
    private String serviceRequestCode;
    private String serviceRequestName;
    private UUID serviceOrderId;
    private String serviceOrderCode;
    private String serviceOrderName;
    private UUID serviceActId;
    private String serviceActCode;
    private String serviceActName;
    private String serviceDescription;
    private String serviceCategory;
    private String unit;
    private String unitShortName;
    private String period;
    private String stage;
    private UUID budgetItemId;
    private String budgetItemCode;
    private String budgetItemName;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal receivedQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal damagedQuantity;
    private String shortageReason;
    private String receiptBatchNumber;
    private UUID receiptWarehouseId;
    private String receiptWarehouseCode;
    private String receiptWarehouseName;
    private UUID receiptStorageLocationId;
    private String receiptStorageLocationCode;
    private String receiptStorageLocationName;
    private String qualityStatus;
    private String accreditationWarning;
    private boolean sourceAllocationAvailable;
    private List<PurchaseOrderSourceAllocationLineageResponse> sourceAllocations;
}

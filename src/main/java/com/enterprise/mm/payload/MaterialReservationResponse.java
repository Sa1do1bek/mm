package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialReservationResponse extends BaseRecordDto {
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private String unitOfMeasure;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private UUID storageLocationId;
    private String storageLocationCode;
    private String storageLocationName;
    private UUID storageBinId;
    private String storageBinCode;
    private String dispositionSnapshot;
    private UUID uomIdSnapshot;
    private String uomCodeSnapshot;
    private UUID stockBatchId;
    private String stockBatchCode;
    private String stockBatchNumber;
    private String stockBatchName;
    private UUID demandPlanId;
    private String demandPlanCode;
    private String demandPlanName;
    private UUID demandPlanItemId;
    private String demandPlanItemCode;
    private String demandPlanItemName;
    private String projectCode;
    private String repairOrderCode;
    private String reservationType;
    private String priority;
    private BigDecimal quantity;
    private BigDecimal reservedQuantity;
    private BigDecimal releasedQuantity;
    private BigDecimal consumedQuantity;
    private BigDecimal cancelledQuantity;
    private BigDecimal expiredQuantity;
    private LocalDate requiredDate;
    private LocalDateTime expiresAt;
    private String referenceType;
    private UUID referenceId;
    private String reservedBy;
    private LocalDateTime releasedAt;
    private String releasedBy;
    private LocalDateTime consumedAt;
    private String consumedBy;
    private LocalDateTime cancelledAt;
    private String cancelledBy;
    private String cancellationReason;
    private LocalDateTime expiredAt;
    private String expiredBy;
    private String expirationReason;
    private LocalDateTime terminalAt;
    private String terminalBy;
    private String terminalReason;
    private String terminalEvidenceReference;
    private List<UUID> reservedMaterialUnitIds;
    private List<String> allowedActions;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getUnitOfMeasureShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unitOfMeasure); }
}

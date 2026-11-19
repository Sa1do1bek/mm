package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockBalanceResponse extends BaseRecordDto {
    private UUID materialGroupId;
    private String materialGroupCode;
    private String materialGroupName;
    private UUID unitOfMeasureId;
    private java.time.LocalDate manufacturedOn;
    private java.time.LocalDate receivedDate;
    private String manufacturerBatchNumber;
    private String currencyCode;
    private String unitCode;
    private String unitLabel;
    private int quantityScale = 4;
    private java.time.LocalDate expirationDate;
    private Long daysUntilExpiry;
    private com.enterprise.mm.enums.StockExpiryStatus expiryStatus;
    private boolean expiryWarning;
    private java.time.LocalDate expiryAsOfDate;
    private String unavailableReason;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal executableQuantity;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private UUID storageLocationId;
    private String storageLocationCode;
    private String storageLocationName;
    private UUID storageBinId;
    private String storageBinCode;
    private String storageBinName;
    private UUID stockBatchId;
    private String stockBatchCode;
    private String stockBatchName;
    private String stockBatchNumber;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal quantity;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal availableQuantity;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal reservedQuantity;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal quarantineQuantity;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal totalValue;
    private LocalDateTime balanceAt;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private UUID ownerPartyId;
    private String stockCategory;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getUnitShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unitLabel); }
}

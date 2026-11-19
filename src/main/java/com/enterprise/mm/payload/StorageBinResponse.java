package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StorageBinResponse extends BaseRecordDto {
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private UUID storageLocationId;
    private String storageLocationCode;
    private String storageLocationName;
    private String binCode;
    private String barcode;
    private String qrCode;
    private String rfidTag;
    private BigDecimal capacityQuantity;
    private UUID capacityUomId;
    private String capacityUomCode;
    private String capacityUomLabel;
    private BigDecimal maxWeight;
    private boolean allowMixedMaterials;
}

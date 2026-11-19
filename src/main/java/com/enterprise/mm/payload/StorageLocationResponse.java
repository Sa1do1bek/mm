package com.enterprise.mm.payload;

import com.enterprise.mm.enums.StorageZoneType;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StorageLocationResponse extends BaseRecordDto {
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String zoneCode;
    private String binCode;
    private String barcode;
    private String qrCode;
    private String rfidTag;
    private StorageZoneType storageZoneType;
    private String temperatureZone;
    private String hazardClassAllowed;
    private BigDecimal capacityQuantity;
    private BigDecimal maxWeight;
    private boolean allowMixedMaterials;
}

package com.enterprise.mm.payload;

import com.enterprise.mm.enums.StorageZoneType;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StorageLocationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID warehouseId;
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

package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StorageBinCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID warehouseId;
    @NotNull
    private UUID storageLocationId;
    @Size(max = 80)
    private String binCode;
    private String barcode;
    private String qrCode;
    private String rfidTag;
    private BigDecimal capacityQuantity;
    private UUID capacityUomId;
    private BigDecimal maxWeight;
    private boolean allowMixedMaterials = true;
}

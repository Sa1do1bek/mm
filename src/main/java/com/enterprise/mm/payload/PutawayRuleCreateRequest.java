package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PutawayRuleCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private Integer priority;
    private UUID warehouseId;
    private UUID materialId;
    private UUID materialGroupId;
    private String hazardClass;
    private String temperatureZone;
    private String abcClass;
    private String storageZoneCode;
    private UUID targetStorageLocationId;
    private BigDecimal maxQuantity;
    private Integer minShelfLifeDays;
    private boolean allowMixedMaterials;
    private boolean allowCrossDock;
}

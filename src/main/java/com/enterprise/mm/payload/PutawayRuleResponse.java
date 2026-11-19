package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PutawayRuleResponse extends BaseRecordDto {
    private Integer priority;
    private UUID warehouseId;
    private UUID materialId;
    private String materialName;
    private UUID materialGroupId;
    private String materialGroupName;
    private String hazardClass;
    private String temperatureZone;
    private String abcClass;
    private String storageZoneCode;
    private UUID targetStorageLocationId;
    private String targetStorageLocationName;
    private BigDecimal maxQuantity;
    private Integer minShelfLifeDays;
    private boolean allowMixedMaterials;
    private boolean allowCrossDock;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "putaway_rules")
public class PutawayRule extends BusinessEntity {
    @Column(nullable = false)
    private Integer priority = 100;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "material_id")
    private UUID materialId;

    @Column(name = "material_group_id")
    private UUID materialGroupId;

    @Column(name = "hazard_class", length = 40)
    private String hazardClass;

    @Column(name = "temperature_zone", length = 80)
    private String temperatureZone;

    @Column(name = "abc_class", length = 10)
    private String abcClass;

    @Column(name = "storage_zone_code", length = 80)
    private String storageZoneCode;

    @Column(name = "target_storage_location_id")
    private UUID targetStorageLocationId;

    @Column(name = "max_quantity", precision = 22, scale = 7)
    private BigDecimal maxQuantity;

    @Column(name = "min_shelf_life_days")
    private Integer minShelfLifeDays;

    @Column(name = "allow_mixed_materials", nullable = false)
    private boolean allowMixedMaterials = true;

    @Column(name = "allow_cross_dock", nullable = false)
    private boolean allowCrossDock;
}
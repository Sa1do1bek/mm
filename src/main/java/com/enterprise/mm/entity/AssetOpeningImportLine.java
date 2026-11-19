package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "asset_opening_import_lines")
public class AssetOpeningImportLine extends BusinessEntity {
    @Column(name = "asset_opening_import_id", nullable = false)
    private UUID assetOpeningImportId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "equipment_id")
    private UUID equipmentId;

    @Column(name = "inventory_number", length = 80)
    private String inventoryNumber;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 30)
    private FixedAssetLifecycleStatus lifecycleStatus = FixedAssetLifecycleStatus.IN_WAREHOUSE;

    @Enumerated(EnumType.STRING)
    @Column(name = "custody_kind", nullable = false, length = 30)
    private FixedAssetCustodyKind custodyKind = FixedAssetCustodyKind.WAREHOUSE;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "acquisition_value", precision = 19, scale = 4)
    private BigDecimal acquisitionValue;

    @Column(name = "validation_status", length = 30)
    private String validationStatus;

    @Column(name = "validation_message", columnDefinition = "text")
    private String validationMessage;
}

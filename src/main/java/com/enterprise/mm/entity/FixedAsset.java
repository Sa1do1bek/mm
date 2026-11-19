package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "fixed_assets")
public class FixedAsset extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "equipment_id")
    private UUID equipmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipment_id", insertable = false, updatable = false)
    private Equipment equipment;

    @Column(name = "source_acceptance_unit_id")
    private UUID sourceAcceptanceUnitId;

    @Column(name = "opening_source_type", length = 40)
    private String openingSourceType;

    @Column(name = "opening_source_id")
    private UUID openingSourceId;

    @Column(name = "asset_code", length = 80)
    private String assetCode;

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

    @Column(name = "custodian_employee_id")
    private UUID custodianEmployeeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custodian_employee_id", insertable = false, updatable = false)
    private Employee custodianEmployee;

    @Column(name = "department_id")
    private UUID departmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    private Department department;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "storage_location_id")
    private UUID storageLocationId;

    @Column(name = "operating_location", length = 240)
    private String operatingLocation;

    @Column(name = "active_transfer_id")
    private UUID activeTransferId;

    @Column(name = "acquisition_value", precision = 19, scale = 4)
    private BigDecimal acquisitionValue;

    @Column(name = "acquisition_currency_code", length = 3)
    private String acquisitionCurrencyCode;

    @Column(name = "acquisition_exchange_rate", precision = 19, scale = 6)
    private BigDecimal acquisitionExchangeRate;

    @Column(name = "acquisition_evidence_type", length = 40)
    private String acquisitionEvidenceType;

    @Column(name = "acquisition_evidence_id")
    private UUID acquisitionEvidenceId;

    @Column(name = "first_commissioned_on")
    private LocalDate firstCommissionedOn;

    @Column(name = "first_commissioned_at")
    private LocalDateTime firstCommissionedAt;

    @Column(name = "first_commissioned_by", length = 120)
    private String firstCommissionedBy;

    @Column(name = "disposed_at")
    private LocalDateTime disposedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 50)
    private com.enterprise.mm.enums.AssetCategory category = com.enterprise.mm.enums.AssetCategory.MACHINERY_EQUIPMENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "depreciation_method", length = 40)
    private com.enterprise.mm.enums.DepreciationMethod depreciationMethod = com.enterprise.mm.enums.DepreciationMethod.STRAIGHT_LINE;

    @Column(name = "useful_life_months")
    private Integer usefulLifeMonths = 60;

    @Column(name = "salvage_value", precision = 19, scale = 4)
    private BigDecimal salvageValue = BigDecimal.ZERO;

    @Column(name = "accumulated_depreciation", precision = 19, scale = 4)
    private BigDecimal accumulatedDepreciation = BigDecimal.ZERO;

    @Column(name = "current_book_value", precision = 19, scale = 4)
    private BigDecimal currentBookValue;

    @Column(name = "depreciation_start_date")
    private LocalDate depreciationStartDate;

    @Column(name = "last_depreciation_date")
    private LocalDate lastDepreciationDate;

    @Column(name = "depreciation_rate", precision = 10, scale = 4)
    private BigDecimal depreciationRate = BigDecimal.ZERO;
}

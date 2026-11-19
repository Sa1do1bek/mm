package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "asset_management_settings",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_asset_management_settings_tenant_company",
        columnNames = {"tenant_id", "company_id"}
    )
)
public class AssetManagementSettings extends BusinessEntity {
    @Column(name = "central_warehouse_id", nullable = false)
    private UUID centralWarehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "central_warehouse_id", insertable = false, updatable = false)
    private Warehouse centralWarehouse;

    @Column(name = "central_storage_location_id")
    private UUID centralStorageLocationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "central_storage_location_id", insertable = false, updatable = false)
    private StorageLocation centralStorageLocation;

    @Column(name = "inventory_number_prefix", nullable = false, length = 30)
    private String inventoryNumberPrefix = "FA";

    @Column(name = "inventory_number_next_value", nullable = false)
    private Long inventoryNumberNextValue = 1L;

    @Column(name = "inventory_number_padding", nullable = false)
    private Integer inventoryNumberPadding = 6;

    @Column(name = "auto_number_at_receipt", nullable = false)
    private boolean autoNumberAtReceipt = false;

    @Column(name = "require_serial_number", nullable = false)
    private boolean requireSerialNumber = false;
}

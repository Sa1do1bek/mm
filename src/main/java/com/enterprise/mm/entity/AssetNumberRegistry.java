package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.AssetNumberRegistrySource;
import com.enterprise.mm.enums.AssetNumberRegistryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "asset_number_registry",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_asset_number_registry_inventory",
            columnNames = {"tenant_id", "company_id", "inventory_number"}
        ),
        @UniqueConstraint(
            name = "uk_asset_number_registry_normalized",
            columnNames = {"tenant_id", "company_id", "normalized_number"}
        )
    }
)
public class AssetNumberRegistry extends BusinessEntity {
    @Column(name = "inventory_number", nullable = false, length = 80)
    private String inventoryNumber;

    @Column(name = "normalized_number", nullable = false, length = 80)
    private String normalizedNumber;

    @Column(name = "fixed_asset_id")
    private UUID fixedAssetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixed_asset_id", insertable = false, updatable = false)
    private FixedAsset fixedAsset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AssetNumberRegistrySource source = AssetNumberRegistrySource.MANUAL;

    @Column(name = "reserved_at", nullable = false)
    private LocalDateTime reservedAt;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "corrected_at")
    private LocalDateTime correctedAt;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    public AssetNumberRegistryStatus getRegistryStatus() {
        return getStatus() == null ? null : AssetNumberRegistryStatus.valueOf(getStatus());
    }

    public void setRegistryStatus(AssetNumberRegistryStatus status) {
        setStatus(status == null ? null : status.name());
    }
}

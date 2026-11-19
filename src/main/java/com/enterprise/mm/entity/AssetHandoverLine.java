package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.AssetHandoverLineStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "asset_handover_lines")
public class AssetHandoverLine extends BusinessEntity {
    @Column(name = "asset_handover_id", nullable = false)
    private UUID assetHandoverId;

    @Column(name = "fixed_asset_id", nullable = false)
    private UUID fixedAssetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixed_asset_id", insertable = false, updatable = false)
    private FixedAsset fixedAsset;

    @Column(name = "asset_allocation_id")
    private UUID assetAllocationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_allocation_id", insertable = false, updatable = false)
    private AssetAllocation assetAllocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_status", nullable = false, length = 30)
    private AssetHandoverLineStatus lineStatus = AssetHandoverLineStatus.PENDING;
}

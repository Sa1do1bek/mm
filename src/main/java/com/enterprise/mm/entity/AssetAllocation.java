package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.AssetAllocationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "asset_allocations")
public class AssetAllocation extends BusinessEntity {
    @Column(name = "fixed_asset_id", nullable = false)
    private UUID fixedAssetId;

    @Column(name = "asset_handover_id", nullable = false)
    private UUID assetHandoverId;

    @Column(name = "allocated_at", nullable = false)
    private LocalDateTime allocatedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "release_reason", length = 80)
    private String releaseReason;

    public AssetAllocationStatus getAllocationStatus() {
        return getStatus() == null ? null : AssetAllocationStatus.valueOf(getStatus());
    }

    public void setAllocationStatus(AssetAllocationStatus status) {
        setStatus(status == null ? null : status.name());
    }
}

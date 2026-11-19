package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.AssetAcceptanceUnitQualityDecision;
import com.enterprise.mm.enums.MaterialType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "asset_acceptance_units")
public class AssetAcceptanceUnit extends BusinessEntity {
    @Column(name = "asset_acceptance_line_id", nullable = false)
    private UUID assetAcceptanceLineId;

    @Column(name = "purchase_order_item_id")
    private UUID purchaseOrderItemId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_type_snapshot", nullable = false, length = 30)
    private MaterialType materialTypeSnapshot = MaterialType.FIXED_ASSET;

    @Column(name = "line_sequence")
    private Integer lineSequence;

    @Column(name = "receiving_unit_number", nullable = false, length = 120)
    private String receivingUnitNumber;

    @Column(name = "manufacturer_serial_number", length = 120)
    private String manufacturerSerialNumber;

    /**
     * Legacy alias kept while older clients and rows are migrated to manufacturerSerialNumber.
     */
    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    /**
     * Legacy alias kept while older rows are migrated to receivingUnitNumber.
     */
    @Column(name = "temporary_tag", length = 120)
    private String temporaryTag;

    @Column(name = "condition", length = 80)
    private String condition;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_decision", nullable = false, length = 30)
    private AssetAcceptanceUnitQualityDecision qualityDecision = AssetAcceptanceUnitQualityDecision.PENDING;

    @Column(name = "quality_decision_at")
    private LocalDateTime qualityDecisionAt;

    @Column(name = "quality_decision_by", length = 120)
    private String qualityDecisionBy;

    @Column(name = "posted_fixed_asset_id")
    private UUID postedFixedAssetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posted_fixed_asset_id", insertable = false, updatable = false)
    private FixedAsset postedFixedAsset;
}

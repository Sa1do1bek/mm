package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.AssetAcceptanceLineQualityDecision;
import com.enterprise.mm.enums.MaterialType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "asset_acceptance_lines")
public class AssetAcceptanceLine extends BusinessEntity {
    @Column(name = "asset_acceptance_id", nullable = false)
    private UUID assetAcceptanceId;

    @Column(name = "purchase_order_item_id")
    private UUID purchaseOrderItemId;

    @Column(name = "contract_specification_id")
    private UUID contractSpecificationId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_type_snapshot", nullable = false, length = 30)
    private MaterialType materialTypeSnapshot = MaterialType.FIXED_ASSET;

    @Column(length = 80)
    private String unit;

    @Column(name = "delivered_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal deliveredQuantity = BigDecimal.ZERO;

    @Column(name = "accepted_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;

    @Column(name = "rejected_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;

    @Column(name = "pending_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal pendingQuantity = BigDecimal.ZERO;

    @Column(name = "hold_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal holdQuantity = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_decision", nullable = false, length = 30)
    private AssetAcceptanceLineQualityDecision qualityDecision = AssetAcceptanceLineQualityDecision.PENDING;

    @Column(name = "accepted_value", precision = 19, scale = 4)
    private BigDecimal acceptedValue;

    @Column(name = "accepted_currency_code", length = 3)
    private String acceptedCurrencyCode;

    @Column(name = "po_received_quantity_recorded", nullable = false, precision = 22, scale = 7)
    private BigDecimal poReceivedQuantityRecorded = BigDecimal.ZERO;

    @Column(name = "po_accepted_quantity_recorded", nullable = false, precision = 22, scale = 7)
    private BigDecimal poAcceptedQuantityRecorded = BigDecimal.ZERO;

    @Column(name = "po_rejected_quantity_recorded", nullable = false, precision = 22, scale = 7)
    private BigDecimal poRejectedQuantityRecorded = BigDecimal.ZERO;

    @Column(name = "po_fulfillment_recorded_at")
    private LocalDateTime poFulfillmentRecordedAt;

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_acceptance_line_id", insertable = false, updatable = false)
    private List<AssetAcceptanceUnit> units = new ArrayList<>();
}

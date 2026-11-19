package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.enums.ValuationMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

@Getter
@Setter
@Entity
@Table(name = "materials")
public class Material extends BusinessEntity {
    @Column(name = "manual_receipt_stock_account_id")
    private UUID manualReceiptStockAccountId;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name="source_data",columnDefinition="jsonb")
    private java.util.Map<String,Object> sourceData;

    @Column(name = "incoming_inspection_required", nullable = false)
    private boolean incomingInspectionRequired;
    @Column(name = "material_group_id")
    private UUID materialGroupId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_group_id", insertable = false, updatable = false)
    private MaterialGroup materialGroup;
    @Column(name = "unit_of_measure_id")
    private UUID unitOfMeasureId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_of_measure_id", insertable = false, updatable = false)
    private UnitOfMeasure unitOfMeasure;
    @Enumerated(EnumType.STRING)
    @Column(name = "material_type", nullable = false, length = 30)
    private MaterialType materialType = MaterialType.TMB;
    @Enumerated(EnumType.STRING)
    @Column(name = "material_subtype", length = 30)
    private MaterialSubtype materialSubtype;
    @Enumerated(EnumType.STRING)
    @Column(name = "tracking_mode", nullable = false, length = 20)
    private TrackingMode trackingMode = TrackingMode.QUANTITY;
    @Enumerated(EnumType.STRING)
    @Column(name = "batch_tracking_policy", nullable = false, length = 20)
    private BatchTrackingPolicy batchTrackingPolicy = BatchTrackingPolicy.NONE;
    @Enumerated(EnumType.STRING)
    @Column(name = "serial_tracking_policy", nullable = false, length = 20)
    private SerialTrackingPolicy serialTrackingPolicy = SerialTrackingPolicy.NONE;
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ValuationMethod valuationMethod = ValuationMethod.ACTUAL_COST;
    @Column(precision = 19, scale = 4)
    private BigDecimal minStock = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal safetyStock = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal reorderPoint = BigDecimal.ZERO;
    @Column(length = 120)
    private String barcode;
    @Column(length = 160)
    private String qrCode;
    @Column(length = 160)
    private String rfidTag;
    @Column(name = "analytics_1", length = 160)
    private String analytics1;
    @Column(name = "analytics_2", length = 160)
    private String analytics2;
    @Column(name = "analytics_3", length = 160)
    private String analytics3;
    @Column(name = "ikpu_code", length = 40)
    private String mxikCode;
    @ManyToOne(fetch = FetchType.LAZY)
    // Imported codes may be absent from the catalog; keep the code and treat the association as null.
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(
        name = "ikpu_code",
        referencedColumnName = "kod",
        insertable = false,
        updatable = false,
        foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)
    )
    private MXIK mxik;
    @Column(name = "tnved_code_id")
    private UUID tnvedCodeId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tnved_code_id", insertable = false, updatable = false)
    private TnvedCode tnvedCodeRef;
    @Column(length = 40)
    private String tnvedCode;
    @Column(length = 120)
    private String article;
    @Column(name = "manufacturer_id")
    private UUID manufacturerId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manufacturer_id", insertable = false, updatable = false)
    private Manufacturer manufacturerRef;
    @Column(length = 240)
    private String manufacturer;
    @Column(length = 120)
    private String countryOfOrigin;
    private Integer shelfLifeDays;
    @Column(length = 40)
    private String hazardClass;
    @Column(precision = 19, scale = 4)
    private BigDecimal maxStock;
    @Column(name = "replenishment_lead_time_days")
    private Integer replenishmentLeadTimeDays;
    @Column(name = "average_cost", precision = 19, scale = 4)
    private BigDecimal averageCost;
    @Column(name = "average_cost_currency_code", length = 3)
    private String averageCostCurrencyCode;
    @Column(name = "average_cost_observation_id")
    private UUID averageCostObservationId;
    @Column(name = "average_cost_source_type", length = 40)
    private String averageCostSourceType;
    @Column(name = "average_cost_source_entity_id")
    private UUID averageCostSourceEntityId;
    @Column(name = "average_cost_source_line_id")
    private UUID averageCostSourceLineId;
    @Column(name = "average_cost_document_code", length = 120)
    private String averageCostDocumentCode;
    @Column(name = "average_cost_observed_at")
    private LocalDateTime averageCostObservedAt;
    @Column(name = "average_cost_selected_at")
    private LocalDateTime averageCostSelectedAt;
    @Column(name = "replacement_material_id")
    private UUID replacementMaterialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replacement_material_id", insertable = false, updatable = false)
    private Material replacementMaterial;
    @Column(name = "lifecycle_reason", columnDefinition = "text")
    private String lifecycleReason;
    @Column(name = "lifecycle_changed_at")
    private LocalDateTime lifecycleChangedAt;
}

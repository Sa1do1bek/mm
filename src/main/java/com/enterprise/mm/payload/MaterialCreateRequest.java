package com.enterprise.mm.payload;

import com.enterprise.mm.enums.HazardClass;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.enums.ValuationMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialCreateRequest {
    private Boolean incomingInspectionRequired;
    private UUID materialGroupId;
    private UUID unitOfMeasureId;
    private MaterialType materialType;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private MaterialSubtype materialSubtype;
    private TrackingMode trackingMode;
    private BatchTrackingPolicy batchTrackingPolicy;
    private SerialTrackingPolicy serialTrackingPolicy;
    private ValuationMethod valuationMethod;
    @PositiveOrZero
    private BigDecimal minStock;
    @PositiveOrZero
    private BigDecimal safetyStock;
    @PositiveOrZero
    private BigDecimal reorderPoint;
    @Size(max = 120)
    private String barcode;
    @Size(max = 160)
    private String qrCode;
    @Size(max = 160)
    private String rfidTag;
    @Size(max = 160)
    private String analytics1;
    @Size(max = 160)
    private String analytics2;
    @Size(max = 160)
    private String analytics3;
    private Long mxikId;
    @Size(max = 40)
    private String mxikCode;
    private UUID tnvedCodeId;
    @Size(max = 40)
    private String tnvedCode;
    @Size(max = 40)
    private String article;
    private UUID manufacturerId;
    @Size(max = 240)
    private String manufacturer;
    @Size(max = 120)
    private String countryOfOrigin;
    @PositiveOrZero
    private Integer shelfLifeDays;
    private HazardClass hazardClass;
    @PositiveOrZero
    private BigDecimal maxStock;
    @PositiveOrZero
    private Integer replenishmentLeadTimeDays;
    private UUID averageCostObservationId;
    @PositiveOrZero
    private BigDecimal averageCost;
    @Size(max = 3)
    private String averageCostCurrencyCode;
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private MaterialStatus status;
    private String description;
    private List<@Valid MaterialAttributeRequest> attributes;
}

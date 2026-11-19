package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockResponse extends BaseRecordDto {
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private String materialStatus;
    private String article;
    private String materialArticle;
    private String materialUnitOfMeasure;
    private String materialCountryOfOrigin;
    private String materialManufacturer;
    private BigDecimal estimatedValue;
    private String valueCurrencyCode;
    private String valueHealth;
    private String valueHealthMessage;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private BigDecimal quantity;
    private Integer monthsWithoutMovement;
    private LocalDateTime detectedAt;
    private String plannedAction;
    private String actionComment;
    private Boolean caseCreationEligible;
    private UUID activeCaseId;
    private String activeCaseNo;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getUnitOfMeasureShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(materialUnitOfMeasure); }
}

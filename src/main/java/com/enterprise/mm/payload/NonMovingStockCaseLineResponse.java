package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockCaseLineResponse extends BaseRecordDto {
    private UUID caseId;
    private UUID nonMovingStockId;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private UUID storageLocationId;
    private String storageLocationCode;
    private String storageLocationName;
    private UUID batchId;
    private String batchCode;
    private String batchName;
    private BigDecimal quantity;
    private BigDecimal availableQuantity;
    private BigDecimal reservedQuantity;
    private BigDecimal blockedQuantity;
    private BigDecimal bookValue;
    private BigDecimal unitCost;
    private String currencyCode;
    private String valuationHealth;
    private String valuationHealthMessage;
    private LocalDate valuationDate;
    private UUID analysisRunId;
    private String detectionRule;
    private String detectionRuleVersion;
    private Integer detectionThreshold;
    private LocalDate analysisAsOfDate;
    private String exclusionResult;
    private boolean activeMembership;
    private Integer monthsWithoutMovement;
    private Integer agingBucketMonths;
    private LocalDateTime lastMovementAt;
    private UUID replacementMaterialId;
    private String replacementMaterialCode;
    private String replacementMaterialName;
    private String materialStatus;
}

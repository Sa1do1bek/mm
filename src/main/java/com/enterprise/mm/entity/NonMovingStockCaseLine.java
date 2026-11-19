package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "non_moving_stock_case_lines")
public class NonMovingStockCaseLine extends BusinessEntity {
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "non_moving_stock_id", nullable = false)
    private UUID nonMovingStockId;
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Column(name = "storage_location_id")
    private UUID storageLocationId;
    @Column(name = "batch_id")
    private UUID batchId;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;
    @Column(name = "available_quantity", precision = 22, scale = 7, nullable = false)
    private BigDecimal availableQuantity = BigDecimal.ZERO;
    @Column(name = "reserved_quantity", precision = 22, scale = 7, nullable = false)
    private BigDecimal reservedQuantity = BigDecimal.ZERO;
    @Column(name = "blocked_quantity", precision = 22, scale = 7, nullable = false)
    private BigDecimal blockedQuantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal bookValue = BigDecimal.ZERO;
    @Column(name = "unit_cost", precision = 19, scale = 4, nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(name = "currency_code", length = 3)
    private String currencyCode;
    @Column(name = "valuation_health", length = 20, nullable = false)
    private String valuationHealth = "PARTIAL";
    @Column(name = "valuation_health_message", columnDefinition = "text")
    private String valuationHealthMessage =
        "Valuation provenance was not explicitly captured; review the persisted accounting currency before use.";
    private LocalDate valuationDate;
    @Column(name = "analysis_run_id")
    private UUID analysisRunId;
    @Column(name = "detection_rule", length = 120)
    private String detectionRule;
    @Column(name = "detection_rule_version", length = 40)
    private String detectionRuleVersion;
    private Integer detectionThreshold;
    private LocalDate analysisAsOfDate;
    @Column(name = "exclusion_result", length = 80)
    private String exclusionResult;
    @Column(name = "active_membership", nullable = false)
    private boolean activeMembership = true;
    private Integer monthsWithoutMovement;
    private Integer agingBucketMonths;
    private LocalDateTime lastMovementAt;
    @Column(name = "replacement_material_id")
    private UUID replacementMaterialId;
    @Column(length = 50)
    private String materialStatus;
}

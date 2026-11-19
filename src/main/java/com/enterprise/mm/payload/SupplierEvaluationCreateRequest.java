package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierEvaluationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID supplierId;
    private UUID purchaseOrderId;
    private UUID purchaseOrderItemId;
    private UUID stockMovementId;
    private UUID materialId;
    private BigDecimal orderedQuantity;
    private BigDecimal receivedQuantity;
    private BigDecimal acceptedQuantity;
    private BigDecimal rejectedQuantity;
    private Integer lateDays;
    private BigDecimal deliveryScore;
    private BigDecimal qualityScore;
    private BigDecimal quantityScore;
    private BigDecimal priceScore;
    private BigDecimal claimsScore;
    private BigDecimal totalScore;
    @Size(max = 40)
    private String evaluationSource;
    private String calculationDetailsJson;
    private LocalDateTime evaluatedAt;
}

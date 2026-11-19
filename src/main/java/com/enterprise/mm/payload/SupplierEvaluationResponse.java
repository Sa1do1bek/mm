package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierEvaluationResponse extends BaseRecordDto {
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID purchaseOrderItemId;
    private String purchaseOrderItemCode;
    private String purchaseOrderItemName;
    private UUID stockMovementId;
    private String stockMovementCode;
    private String stockMovementName;
    private UUID materialId;
    private String materialCode;
    private String materialName;
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
    private String evaluationSource;
    private String calculationDetailsJson;
    private LocalDateTime evaluatedAt;
}

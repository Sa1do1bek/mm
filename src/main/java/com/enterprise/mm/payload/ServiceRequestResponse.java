package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceRequestResponse extends BaseRecordDto {
    private String number;
    private String title;
    private String category;
    private String urgency;
    private UUID departmentId;
    private String departmentCode;
    private String departmentName;
    private UUID responsibleUserId;
    private String responsibleUserCode;
    private String responsibleUserName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID budgetItemId;
    private String budgetItemCode;
    private String budgetItemName;
    private UUID costCenterId;
    private BigDecimal estimatedAmount;
    private BigDecimal approvedAmount;
    private BigDecimal actualAmount;
    private String acceptanceMode;
    private BigDecimal orderedAmount;
    private BigDecimal acceptedAmount;
    private BigDecimal rejectedAmount;
    private BigDecimal remainingAmount;
    private String currencyCode;
    private BigDecimal orderedQuantity;
    private BigDecimal acceptedQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal remainingQuantity;
    private String uomCode;
    private String uomShortName;
    private BigDecimal acceptedVolume;
    private BigDecimal rejectedVolume;
    private String acceptanceRemarks;
    private BigDecimal budgetReservedAmount;
    private LocalDateTime budgetReservedAt;
    private LocalDateTime budgetReleasedAt;
    private LocalDateTime budgetConsumedAt;
    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
}

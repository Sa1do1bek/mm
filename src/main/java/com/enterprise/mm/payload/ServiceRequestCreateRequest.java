package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceRequestCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private String number;
    private String title;
    private String category;
    private String urgency;
    private UUID departmentId;
    private UUID responsibleUserId;
    private UUID supplierId;
    private UUID contractId;
    private UUID purchaseOrderId;
    private UUID budgetItemId;
    private UUID costCenterId;
    private BigDecimal estimatedAmount;
    private BigDecimal approvedAmount;
    private BigDecimal actualAmount;
    private String acceptanceMode;
    private BigDecimal orderedAmount;
    private String currencyCode;
    private BigDecimal orderedQuantity;
    private String uomCode;
    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
}

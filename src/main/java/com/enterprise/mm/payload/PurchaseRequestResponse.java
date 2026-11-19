package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseRequestResponse extends BaseRecordDto {
    private UUID departmentId;
    private UUID demandPlanId;
    private UUID warehouseId;
    private String demandPlanCode;
    private String demandPlanName;
    private String requestedBy;
    private String requestType;
    private LocalDate requiredDate;
    private BigDecimal totalAmount;
    private String currencyCode;
}

package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockDecisionLineResponse extends BaseRecordDto {
    private UUID decisionId;
    private UUID caseLineId;
    private String decisionType;
    private BigDecimal approvedQuantity;
    private UUID targetWarehouseId;
    private BigDecimal expectedRecoveryAmount;
    private BigDecimal financialAmount;
    private String currencyCode;
    private LocalDate executionDueDate;
    private String controlledReason;
    private String comment;
    private Integer lineOrder;
}

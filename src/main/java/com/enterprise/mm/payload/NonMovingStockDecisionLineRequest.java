package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockDecisionLineRequest {
    @NotNull
    private UUID caseLineId;
    @NotBlank
    private String decisionType;
    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal approvedQuantity;
    private UUID targetWarehouseId;
    @DecimalMin(value = "0.0")
    private BigDecimal expectedRecoveryAmount;
    @DecimalMin(value = "0.0")
    private BigDecimal financialAmount;
    @Size(max = 3)
    private String currencyCode;
    private LocalDate executionDueDate;
    private String controlledReason;
    private String comment;
}

package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockDecisionCreateRequest {
    private Long expectedVersion;
    private UUID commissionId;
    @Size(max = 120)
    private String commissionProtocolNo;
    private String decisionType;
    private BigDecimal approvedQuantity;
    private UUID targetWarehouseId;
    private BigDecimal salePrice;
    private BigDecimal impairmentReserveAmount;
    private String writeOffReason;
    private String comment;
    private LocalDate nextReviewDate;
    private BigDecimal expectedRecoveryAmount;
    @Size(max = 3)
    private String expectedRecoveryCurrencyCode;
    @Size(max = 160)
    private String recoveryBasisReference;
    @Size(max = 40)
    private String recoveryAllocationBasis;
    @Valid
    private List<NonMovingStockDecisionLineRequest> lines = new ArrayList<>();
}

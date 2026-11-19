package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockDecisionResponse extends BaseRecordDto {
    private UUID caseId;
    private String decisionType;
    private BigDecimal approvedQuantity;
    private UUID targetWarehouseId;
    private BigDecimal salePrice;
    private BigDecimal impairmentReserveAmount;
    private String writeOffReason;
    private String comment;
    private LocalDate nextReviewDate;
    private BigDecimal expectedRecoveryAmount;
    private String expectedRecoveryCurrencyCode;
    private String recoveryBasisReference;
    private String recoveryAllocationBasis;
    private Integer decisionVersion;
    private boolean immutable;
    private LocalDateTime submittedAt;
    private String submittedBy;
    private UUID supersedesDecisionId;
    private List<NonMovingStockDecisionLineResponse> lines = new ArrayList<>();
}

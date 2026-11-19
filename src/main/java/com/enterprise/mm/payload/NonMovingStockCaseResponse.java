package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockCaseResponse extends BaseRecordDto {
    private String caseNo;
    private LocalDateTime detectedAt;
    private LocalDate reviewDueDate;
    private BigDecimal totalQuantity;
    private BigDecimal totalBookValue;
    private String responsibleUser;
    private UUID responsibleUserId;
    private String responsibleUsername;
    private String responsibleDisplayName;
    private String accountingCurrencyCode;
    private String valuationHealth;
    private String valuationHealthMessage;
    private UUID primaryMaterialId;
    private String primaryMaterialCode;
    private String primaryMaterialName;
    private UUID primaryWarehouseId;
    private String primaryWarehouseCode;
    private String primaryWarehouseName;
    private int lineCount;
    private String blockerType;
    private String blockerDetails;
    private LocalDateTime closedAt;
    private String closedBy;
    private String closureReason;
    private LocalDateTime cancelledAt;
    private String cancelledBy;
    private String cancellationReason;
    private LocalDateTime reopenedAt;
    private String reopenedBy;
    private String reopenReason;
    private UUID commissionId;
    private String commissionCode;
    private String commissionName;
    private String commissionProtocolNo;
    private List<NonMovingStockCaseLineResponse> lines = new ArrayList<>();
    private List<NonMovingStockDecisionResponse> decisions = new ArrayList<>();
    private List<NonMovingStockExecutionResponse> executions = new ArrayList<>();
}

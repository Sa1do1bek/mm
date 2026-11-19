package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockExecutionOutputResponse extends BaseRecordDto {
    private UUID executionId;
    private UUID decisionLineId;
    private UUID caseLineId;
    private String outputType;
    private String documentType;
    private UUID documentId;
    private String documentNumber;
    private String outputStatus;
    private BigDecimal quantity;
    private BigDecimal amount;
    private String currencyCode;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime completedAt;
}

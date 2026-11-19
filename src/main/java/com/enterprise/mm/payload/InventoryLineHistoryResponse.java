package com.enterprise.mm.payload;

import com.enterprise.mm.enums.InventoryLineStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class InventoryLineHistoryResponse extends BaseRecordDto {
    private UUID inventoryItemId;
    private Long oldCountRevision;
    private Long newCountRevision;
    private java.util.Map<String,Object> reviewEvidence;
    private UUID inventoryDocumentId;
    private String action;
    private BigDecimal oldCountedQuantity;
    private BigDecimal newCountedQuantity;
    private InventoryLineStatus oldStatus;
    private InventoryLineStatus newStatus;
    private BigDecimal oldDifferenceQuantity;
    private BigDecimal newDifferenceQuantity;
    private String reason;
    private String changedBy;
    private LocalDateTime changedAt;
}

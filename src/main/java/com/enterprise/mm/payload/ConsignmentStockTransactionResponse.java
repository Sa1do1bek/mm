package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConsignmentStockTransactionResponse extends BaseRecordDto {
    private UUID vmiAgreementId;
    private UUID supplierId;
    private UUID materialId;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID stockMovementId;
    private String transactionType;
    private BigDecimal quantity;
    private BigDecimal unitCost;
    private BigDecimal amount;
    private String settlementStatus;
    private String settlementReference;
    private LocalDateTime transactionAt;
}
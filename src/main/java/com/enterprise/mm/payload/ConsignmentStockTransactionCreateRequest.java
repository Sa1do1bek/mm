package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConsignmentStockTransactionCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
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

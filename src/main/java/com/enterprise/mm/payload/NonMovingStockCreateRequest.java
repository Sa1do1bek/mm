package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID materialId;
    private UUID warehouseId;
    private BigDecimal quantity;
    private Integer monthsWithoutMovement;
    private LocalDateTime detectedAt;
    private String plannedAction;
    private String actionComment;
}

package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialReservationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID materialId;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID storageBinId;
    private UUID stockBatchId;
    private UUID demandPlanId;
    private UUID demandPlanItemId;
    private String projectCode;
    private String repairOrderCode;
    private String reservationType;
    private String priority;
    private BigDecimal quantity;
    private LocalDate requiredDate;
    private LocalDateTime expiresAt;
    private String referenceType;
    private UUID referenceId;
    private String reservedBy;
    private List<UUID> materialUnitIds;
}

package com.enterprise.mm.payload;

import com.enterprise.mm.enums.InventoryDiscrepancyType;
import com.enterprise.mm.enums.InventoryLineStatus;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class InventoryItemUpdateRequest  {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private BigDecimal differenceAmount;
    private InventoryLineStatus lineStatus;
    private InventoryDiscrepancyType discrepancyType;
    private String discrepancyReason;
    private String commentText;
}

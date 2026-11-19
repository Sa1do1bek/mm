package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MovementType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockMovementDocumentCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    @NotNull
    private UUID warehouseId;
    private UUID targetWarehouseId;
    @NotNull
    private MovementType movementType;
    private LocalDate movementDate;
    private String referenceType;
    private UUID referenceId;
    @Valid
    @NotEmpty
    private List<StockMovementDocumentItemRequest> items;
}

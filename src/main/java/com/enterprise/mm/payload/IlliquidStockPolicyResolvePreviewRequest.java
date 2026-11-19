package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IlliquidStockPolicyResolvePreviewRequest {
    @NotNull
    private LocalDate asOfDate;
    private String legalEntityCode;
    private UUID warehouseId;
    private String materialCategory;
    private String materialType;
}

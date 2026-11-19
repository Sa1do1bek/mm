package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryCountRequest {
    @NotNull private Long expectedDocumentVersion;
    @NotNull private Long expectedLineVersion;
    private boolean clearCount;
    private BigDecimal countedQuantity;
    private String comment;
}

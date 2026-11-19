package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryScanCountRequest {
    @NotBlank
    private String scannedCode;
    private java.util.UUID lineId;
    private Long expectedDocumentVersion;
    private Long expectedLineVersion;
    private BigDecimal countedQuantity;
    private String comment;
}

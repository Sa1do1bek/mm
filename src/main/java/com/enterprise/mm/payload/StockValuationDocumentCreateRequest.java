package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import com.enterprise.mm.enums.ValuationMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockValuationDocumentCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID materialId;
    private UUID warehouseId;
    private String documentType;
    private ValuationMethod valuationMethod;
    private LocalDate documentDate;
    private BigDecimal quantity;
    private BigDecimal oldUnitCost;
    private BigDecimal newUnitCost;
    private BigDecimal amount;
    private BigDecimal reserveAmount;
    private String reason;
    private LocalDateTime postedAt;
}

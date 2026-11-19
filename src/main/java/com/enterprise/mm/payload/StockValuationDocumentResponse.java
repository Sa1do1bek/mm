package com.enterprise.mm.payload;

import java.math.BigDecimal;
import com.enterprise.mm.enums.ValuationMethod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockValuationDocumentResponse extends BaseRecordDto {
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
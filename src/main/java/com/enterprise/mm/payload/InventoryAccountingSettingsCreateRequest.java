package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Getter
@Setter
public class InventoryAccountingSettingsCreateRequest {
    private UUID defaultStockAccountId;
    private UUID returnablePackagingStockAccountId;
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    @JsonAlias({"valuationMethod", "valuation_method", "methodType", "method_type", "method", "default_valuation_method"})
    private String defaultValuationMethod;
    private boolean allowWarehouseValuationOverride;
    private boolean allowNegativeStock;
    private boolean allowZeroCostReceipt;
    private String periodCloseMode;
    private String baseCurrencyCode;
    private Integer roundingScale;
    private String roundingMode;
    private String revaluationPostingMode;
    private String impairmentPostingMode;
}

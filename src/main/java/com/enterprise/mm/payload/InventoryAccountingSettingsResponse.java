package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryAccountingSettingsResponse extends BaseRecordDto {
    private java.util.UUID defaultStockAccountId;
    private String defaultStockAccountCode;
    private String defaultStockAccountName;
    private java.util.UUID returnablePackagingStockAccountId;
    private String returnablePackagingStockAccountCode;
    private String returnablePackagingStockAccountName;
    private String defaultValuationMethod;
    private boolean allowWarehouseValuationOverride;
    private boolean allowNegativeStock;
    private boolean allowZeroCostReceipt;
    private String periodCloseMode;
    private String baseCurrencyCode;
    private int roundingScale;
    private String roundingMode;
    private String revaluationPostingMode;
    private String impairmentPostingMode;
}

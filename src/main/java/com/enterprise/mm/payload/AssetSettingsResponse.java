package com.enterprise.mm.payload;

import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetSettingsResponse extends BaseRecordDto {
    private UUID centralWarehouseId;
    private UUID centralStorageLocationId;
    private String inventoryNumberPrefix;
    private Long inventoryNumberNextValue;
    private Integer inventoryNumberPadding;
    private boolean autoNumberAtReceipt;
    private boolean requireSerialNumber;
    private boolean centralWarehouseConfigured;
}

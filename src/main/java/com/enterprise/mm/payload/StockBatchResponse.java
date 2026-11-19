package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockBatchResponse extends BaseRecordDto {
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String batchNumber;
    private String serialNumber;
    private String barcode;
    private String qrCode;
    private String rfidTag;
    private BigDecimal quantity;
    private LocalDate receivedDate;
    private LocalDate expirationDate;
    private BigDecimal unitCost;
    private BigDecimal purchaseUnitCost;
    private BigDecimal additionalCostPerUnit;
    private BigDecimal finalUnitCost;
}

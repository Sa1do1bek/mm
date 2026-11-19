package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockBatchCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID materialId;
    private UUID warehouseId;
    private String batchNumber;
    private String serialNumber;
    private String barcode;
    private String qrCode;
    private String rfidTag;
    private BigDecimal quantity;
    private LocalDate receivedDate;
    private LocalDate expirationDate;
    private BigDecimal unitCost;
}

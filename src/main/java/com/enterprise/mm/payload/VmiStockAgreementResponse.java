package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VmiStockAgreementResponse extends BaseRecordDto {
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private UUID storageLocationId;
    private String storageLocationCode;
    private String storageLocationName;
    private String stockType;
    private String supplierSku;
    private BigDecimal minStock;
    private BigDecimal reorderPoint;
    private BigDecimal targetStock;
    private BigDecimal maxStock;
    private Integer leadTimeDays;
    private Integer reviewCycleDays;
    private String ownershipTransferEvent;
    private String settlementCycle;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalDateTime lastReviewedAt;
    private LocalDateTime lastReplenishmentRequestAt;
}
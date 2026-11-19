package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VmiStockAgreementCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID supplierId;
    private UUID materialId;
    private UUID warehouseId;
    private UUID storageLocationId;
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

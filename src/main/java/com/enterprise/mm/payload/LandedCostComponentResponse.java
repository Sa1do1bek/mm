package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LandedCostComponentResponse extends BaseRecordDto {
    private UUID importDeliveryId;
    private String importDeliveryCode;
    private String importDeliveryName;
    private String componentType;
    private BigDecimal amount;
    private String currencyCode;
    private String allocationMethod;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private boolean includedInInventoryCost;
    private LocalDateTime postedAt;
}
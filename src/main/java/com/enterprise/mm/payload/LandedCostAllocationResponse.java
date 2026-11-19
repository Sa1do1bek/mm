package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LandedCostAllocationResponse extends BaseRecordDto {
    private UUID importDeliveryId;
    private String allocationMethod;
    private LocalDate documentDate;
    private String currencyCode;
    private String baseCurrencyCode;
    private BigDecimal exchangeRate;
    private BigDecimal totalAmount;
    private BigDecimal baseTotalAmount;
    private LocalDateTime postedAt;
}
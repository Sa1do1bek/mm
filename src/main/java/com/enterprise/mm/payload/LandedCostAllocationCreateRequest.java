package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LandedCostAllocationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    @NotNull(message = "Import delivery is required")
    private UUID importDeliveryId;
    private String allocationMethod;
    private LocalDate documentDate;
    private String currencyCode;
    private String baseCurrencyCode;
    @DecimalMin(value = "0.0", inclusive = true, message = "Exchange rate cannot be negative")
    private BigDecimal exchangeRate;
    @DecimalMin(value = "0.0", inclusive = true, message = "Total amount cannot be negative")
    private BigDecimal totalAmount;
    private BigDecimal baseTotalAmount;
    private LocalDateTime postedAt;
}

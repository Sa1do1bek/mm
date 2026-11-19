package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CurrencyRateCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID currencyId;
    private String currencyCode;
    private String baseCurrencyCode;
    private LocalDate rateDate;
    private BigDecimal nominal;
    private BigDecimal rate;
    private String source;
    private boolean officialRate;
}

package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExchangeRateResponse {
    private UUID id;
    private CurrencyResponse currency;
    private LocalDate rateDate;
    private BigDecimal rateToBase;
    private String source;
    private LocalDateTime createdAt;
}

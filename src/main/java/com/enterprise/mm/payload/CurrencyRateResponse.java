package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CurrencyRateResponse extends BaseRecordDto {
    private UUID currencyId;
    private String currencyCode;
    private String baseCurrencyCode;
    private LocalDate rateDate;
    private BigDecimal nominal;
    private BigDecimal rate;
    private String source;
    private boolean officialRate;
}
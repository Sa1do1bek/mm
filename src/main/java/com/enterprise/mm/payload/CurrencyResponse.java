package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class CurrencyResponse extends BaseRecordDto {
    private String isoCode;
    private String numericCode;
    private String symbol;
    private Integer minorUnit;
    private boolean baseCurrency;
    private BigDecimal rate;
    private LocalDate rateDate;
}

package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CurrencyCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String isoCode;
    private String numericCode;
    private String symbol;
    private Integer minorUnit;
    private boolean baseCurrency;
}

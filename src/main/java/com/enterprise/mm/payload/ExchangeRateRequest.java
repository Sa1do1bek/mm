package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExchangeRateRequest {
    @NotNull
    private UUID currencyId;

    @NotNull
    private LocalDate rateDate;

    @NotNull
    @DecimalMin(value = "0.000001")
    @Digits(integer = 13, fraction = 6)
    private BigDecimal rateToBase;

    @NotBlank
    @Size(max = 120)
    private String source;
}

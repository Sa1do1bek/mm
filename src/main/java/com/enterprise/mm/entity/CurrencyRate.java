package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "currency_rates")
public class CurrencyRate extends BusinessEntity {
    private UUID currencyId;

    @Column(length = 3)
    private String currencyCode;

    @Column(length = 3)
    private String baseCurrencyCode;

    private LocalDate rateDate;

    @Column(precision = 19, scale = 6)
    private BigDecimal nominal;

    @Column(precision = 19, scale = 6)
    private BigDecimal rate;

    @Column(length = 120)
    private String source;

    private boolean officialRate;
}

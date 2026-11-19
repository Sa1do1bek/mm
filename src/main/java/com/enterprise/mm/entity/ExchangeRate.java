package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "exchange_rates",
    indexes = {
        @Index(name = "idx_exchange_rates_currency_rate_date", columnList = "currency_id, rate_date"),
        @Index(name = "idx_exchange_rates_tenant_currency_rate_date", columnList = "tenant_id, currency_id, rate_date")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_exchange_rates_currency_rate_date_source",
            columnNames = {"currency_id", "rate_date", "source"}
        )
    }
)
public class ExchangeRate extends BusinessEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "currency_id", nullable = false)
    private Currency currency;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "rate_to_base", nullable = false, precision = 19, scale = 6)
    private BigDecimal rateToBase;

    @Column(nullable = false, length = 120)
    private String source;
}

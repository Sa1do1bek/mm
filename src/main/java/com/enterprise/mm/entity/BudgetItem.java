package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "budget_items")
public class BudgetItem extends BusinessEntity {
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";
    @Column(precision = 19, scale = 4)
    private BigDecimal annualLimit = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal reservedAmount = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal committedAmount = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal usedAmount = BigDecimal.ZERO;
}

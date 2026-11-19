package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "currencies")
public class Currency extends BusinessEntity {
    @Column(length = 3)
    private String isoCode;

    @Column(length = 3)
    private String numericCode;

    @Column(length = 12)
    private String symbol;

    private Integer minorUnit;

    private boolean baseCurrency;
}

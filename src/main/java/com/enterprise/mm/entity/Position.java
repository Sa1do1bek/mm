package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "positions")
public class Position extends BusinessEntity {
    @Column(length = 120)
    private String externalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(length = 80)
    private String positionCategory;

    @Column(length = 40)
    private String grade;

    @Column(precision = 19, scale = 4)
    private BigDecimal headcount;

    @Column(precision = 19, scale = 4)
    private BigDecimal occupiedHeadcount;
}

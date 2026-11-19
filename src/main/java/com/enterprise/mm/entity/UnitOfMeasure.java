package com.enterprise.mm.entity;

import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "unit_of_measures")
public class UnitOfMeasure extends BusinessEntity {

    @Column(length = 120)
    private String measurementNameShort;

    @Column(length = 240)
    private String measurementNameLong;

    @Column(length = 120)
    private String measurementNameShortRu;

    @Column(length = 240)
    private String measurementNameLongRu;

    @Column(length = 120)
    private String measurementNameShortUz;

    @Column(length = 240)
    private String measurementNameLongUz;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private MeasurementUnitType unitType;

    /** Null retains legacy stock rules: integral pieces and four decimal places otherwise. */
    @Column(name = "stock_quantity_scale")
    private Integer stockQuantityScale;
}

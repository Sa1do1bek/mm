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
@Table(name = "material_consumption_norms")
public class MaterialConsumptionNorm extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "equipment_id")
    private UUID equipmentId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "product_code", length = 120)
    private String productCode;

    @Column(name = "operation_code", length = 120)
    private String operationCode;

    @Column(name = "norm_type", nullable = false, length = 40)
    private String normType;

    @Column(name = "base_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal baseQuantity = BigDecimal.ZERO;

    @Column(name = "output_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal outputQuantity = BigDecimal.ONE;

    @Column(name = "unit_of_measure_id")
    private UUID unitOfMeasureId;

    @Column(name = "loss_percent", nullable = false, precision = 8, scale = 4)
    private BigDecimal lossPercent = BigDecimal.ZERO;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;
}
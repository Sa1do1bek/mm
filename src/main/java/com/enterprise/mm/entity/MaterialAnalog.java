package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_analogs", uniqueConstraints = @UniqueConstraint(name = "uk_material_analog_pair", columnNames = {"tenant_id", "company_id", "material_id", "analog_material_id", "is_delete"}))
public class MaterialAnalog extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "analog_material_id", nullable = false)
    private UUID analogMaterialId;

    @Column(name = "analog_type", nullable = false, length = 40)
    private String analogType;

    @Column(name = "interchangeability", length = 40)
    private String interchangeability;

    @Column(name = "conversion_factor", nullable = false, precision = 19, scale = 6)
    private BigDecimal conversionFactor = BigDecimal.ONE;

    @Column(nullable = false)
    private Integer priority = 100;

    @Column(name = "approved_by", length = 120)
    private String approvedBy;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;
}

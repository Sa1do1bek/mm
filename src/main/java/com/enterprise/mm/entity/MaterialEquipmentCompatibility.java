package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_equipment_compatibilities", uniqueConstraints = @UniqueConstraint(name = "uk_material_equipment_compatibility", columnNames = {"tenant_id", "company_id", "material_id", "equipment_id", "compatibility_type", "is_delete"}))
public class MaterialEquipmentCompatibility extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "equipment_id", nullable = false)
    private UUID equipmentId;

    @Column(name = "compatibility_type", nullable = false, length = 40)
    private String compatibilityType;

    @Column(name = "manufacturer_part_no", length = 120)
    private String manufacturerPartNo;

    @Column(name = "required_flag", nullable = false)
    private boolean required;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;
}

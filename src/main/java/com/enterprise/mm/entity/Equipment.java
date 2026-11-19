package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "equipment", uniqueConstraints = @UniqueConstraint(name = "uk_equipment_code", columnNames = {"tenant_id", "company_id", "code"}))
public class Equipment extends BusinessEntity {
    @Column(name = "external_equipment_id", length = 120)
    private String externalEquipmentId;

    @Column(length = 120)
    private String model;

    @Column(name = "manufacturer_id")
    private UUID manufacturerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manufacturer_id", insertable = false, updatable = false)
    private Manufacturer manufacturerRef;

    @Column(length = 240)
    private String manufacturer;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(length = 240)
    private String location;
}

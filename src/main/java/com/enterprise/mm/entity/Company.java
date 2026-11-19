package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.security.SensitiveStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "companies", uniqueConstraints = @UniqueConstraint(name = "uk_companies_tenant_code", columnNames = {"tenant_id", "code"}))
public class Company extends BusinessEntity {
    @Column(name = "legal_name", length = 300)
    private String legalName;

    @Column(name = "short_name", length = 180)
    private String shortName;

    @Column(name = "tax_id", length = 500)
    @Convert(converter = SensitiveStringConverter.class)
    private String taxId;

    @Column(name = "oked_code", length = 20)
    private String okedCode;

    @Column(name = "registration_number", length = 80)
    private String registrationNumber;

    @Column(name = "registration_date")
    private LocalDate registrationDate;

    @Column(name = "legal_address", length = 500)
    private String legalAddress;

    @Column(name = "phone", length = 80)
    private String phone;

    @Column(name = "email", length = 160)
    private String email;

    @Column(name = "non_moving_stock_threshold_months", nullable = false)
    private Integer nonMovingStockThresholdMonths = 12;

    @Column(name = "non_moving_stock_auto_detect_enabled", nullable = false)
    private Boolean nonMovingStockAutoDetectEnabled = Boolean.FALSE;
}

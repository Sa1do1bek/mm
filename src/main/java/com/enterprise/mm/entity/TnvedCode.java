package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "tnved_codes", uniqueConstraints = @UniqueConstraint(name = "uk_tnved_codes_tenant_company_code", columnNames = {"tenant_id", "company_id", "tnved_code"}))
public class TnvedCode extends BusinessEntity {
    @Column(length = 40, nullable = false)
    private String tnvedCode;

    @Column(length = 40)
    private String parentCode;

    @Column(length = 40)
    private String sectionCode;

    @Column(length = 40)
    private String unitCode;

    @Column(precision = 7, scale = 2)
    private BigDecimal dutyRate;

    @Column(precision = 7, scale = 2)
    private BigDecimal vatRate;

    @Column(length = 40)
    private String sourceSystem;

    @Column(length = 120)
    private String externalId;

    private LocalDateTime lastSyncedAt;
}

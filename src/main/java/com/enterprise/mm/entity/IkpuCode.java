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
@Table(name = "ikpu_codes", uniqueConstraints = @UniqueConstraint(name = "uk_ikpu_codes_tenant_company_code", columnNames = {"tenant_id", "company_id", "ikpu_code"}))
public class IkpuCode extends BusinessEntity {
    @Column(length = 40, nullable = false)
    private String ikpuCode;

    @Column(length = 40)
    private String packageCode;

    @Column(length = 40)
    private String unitCode;

    @Column(length = 80)
    private String groupCode;

    @Column(precision = 7, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "is_service")
    private boolean service;

    @Column(length = 40)
    private String sourceSystem;

    @Column(length = 120)
    private String externalId;

    private LocalDateTime lastSyncedAt;
}

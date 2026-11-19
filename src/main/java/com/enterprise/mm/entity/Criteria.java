package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "qms_criteria",
    uniqueConstraints = @UniqueConstraint(name = "uk_qms_criteria_tenant_company_code", columnNames = {"tenant_id", "company_id", "code"})
)
public class Criteria extends BusinessEntity {
    @Column(name = "max_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal maxScore = BigDecimal.valueOf(20);

    @Column(nullable = false, precision = 7, scale = 3)
    private BigDecimal weight = BigDecimal.ONE;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;
}

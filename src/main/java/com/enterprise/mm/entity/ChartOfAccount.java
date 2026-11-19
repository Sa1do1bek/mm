package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "chart_of_accounts", uniqueConstraints = @UniqueConstraint(name = "uk_chart_of_accounts_tenant_company_code", columnNames = {"tenant_id", "company_id", "account_code"}))
public class ChartOfAccount extends BusinessEntity {
    @Column(name = "account_code", length = 40, nullable = false)
    private String accountCode;

    @Column(name = "parent_code", length = 40)
    private String parentCode;

    @Column(name = "account_type", length = 40)
    private String accountType;

    @Column(name = "is_group", nullable = false)
    private boolean group;

    @Column(name = "source_system", length = 40)
    private String sourceSystem;

    @Column(name = "external_id", length = 120)
    private String externalId;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;
}

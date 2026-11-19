package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "roles", uniqueConstraints = @UniqueConstraint(name = "uk_roles_tenant_company_role_name", columnNames = {"tenant_id", "company_id", "role_name"}))
@AttributeOverride(name = "companyId", column = @Column(name = "company_id", nullable = true))
public class Role extends BusinessEntity {
    @Column(nullable = false, length = 80)
    private String roleName;

    @Transient
    private boolean globalScope;

    @PrePersist
    @PreUpdate
    private void applyGlobalScope() {
        if (globalScope) {
            setCompanyId(null);
        }
    }
}

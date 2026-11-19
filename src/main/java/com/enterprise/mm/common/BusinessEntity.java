package com.enterprise.mm.common;

import com.enterprise.mm.util.BusinessNameGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BusinessEntity {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 80)
    private String tenantId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 120, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "is_delete", nullable = false, columnDefinition = "boolean default false")
    private boolean isDeleted = false;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(length = 80)
    @Size(max = 80)
    private String code;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(length = 50)
    @Size(max = 50)
    private String status;

    @Column(columnDefinition = "text")
    private String description;

    public void softDelete() {
        this.isDeleted = true;
    }

    public void restore() {
        this.isDeleted = false;
    }

    @PrePersist
    @PreUpdate
    void applyContextDefaults() {
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = TenantContext.currentTenant();
        }
        if (companyId == null) {
            companyId = CompanyContext.currentCompany();
        }
        name = BusinessNameGenerator.normalize(name, getClass().getSimpleName());
    }
}

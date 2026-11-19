package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_licenses")
public class SupplierLicense extends BusinessEntity {
    @NotNull
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "license_number", length = 120)
    private String licenseNumber;

    @Column(name = "license_type", length = 120)
    private String licenseType;

    @Column(name = "issuing_authority", length = 240)
    private String issuingAuthority;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "scope", length = 500)
    private String scope;

    @Column(name = "territory", length = 240)
    private String territory;

    @Column(name = "renewal_required", nullable = false)
    private boolean renewalRequired;

    @Column(name = "revoked_at")
    private LocalDate revokedAt;

    @Column(name = "revocation_reason", length = 500)
    private String revocationReason;
}

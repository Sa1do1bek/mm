package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_certificates")
public class SupplierCertificate extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;
    @Column(length = 120)
    private String certificateNumber;
    @Column(length = 240)
    private String issuer;
    private LocalDate validFrom;
    private LocalDate validTo;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
    }
}

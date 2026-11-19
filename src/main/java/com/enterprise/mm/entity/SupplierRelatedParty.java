package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.security.SensitiveStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_related_parties")
public class SupplierRelatedParty extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "party_type", nullable = false, length = 80)
    private String partyType;

    @Column(name = "full_name", length = 240)
    private String fullName;

    @Column(name = "legal_name", length = 300)
    private String legalName;

    @Column(name = "tax_id", length = 500)
    @Convert(converter = SensitiveStringConverter.class)
    private String taxId;

    @Column(name = "country_code", length = 3)
    private String countryCode;

    @Column(name = "ownership_percent", precision = 7, scale = 2)
    private BigDecimal ownershipPercent = BigDecimal.ZERO;

    @Column(name = "position_title", length = 240)
    private String positionTitle;

    @Column(length = 80)
    private String phone;

    @Column(length = 160)
    private String email;

    @Column(name = "pep_flag", nullable = false)
    private boolean pepFlag;

    @Column(name = "sanctions_match", nullable = false)
    private boolean sanctionsMatch;

    @Column(name = "conflict_of_interest_flag", nullable = false)
    private boolean conflictOfInterestFlag;

    @Column(name = "risk_level", nullable = false, length = 40)
    private String riskLevel = "LOW";

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
    }
}

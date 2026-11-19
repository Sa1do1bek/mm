package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.security.SensitiveStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.CascadeType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "suppliers")
public class Supplier extends BusinessEntity {
    @Column(length = 300)
    private String legalName;
    @Column(length = 180)
    private String shortName;
    @Column(length = 500)
    @Convert(converter = SensitiveStringConverter.class)
    private String taxId;
    @Column(name = "base_tax_id", length = 500)
    @Convert(converter = SensitiveStringConverter.class)
    private String baseTaxId;
    @Column(name = "tax_id_suffix")
    private Integer taxIdSuffix;
    @Column(name = "parent_supplier_id")
    private UUID parentSupplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_supplier_id", insertable = false, updatable = false)
    private Supplier parentSupplier;
    @Column(name = "supplier_type", length = 40)
    private String supplierType = "HEAD_OFFICE";
    @Column(name = "relationship_type", length = 20, nullable = false)
    private String relationshipType = "EXTERNAL";
    @Column(name = "owner_employee_id")
    private UUID ownerEmployeeId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_employee_id", insertable = false, updatable = false)
    private Employee ownerEmployee;
    @Column(name = "primary_contact_name", length = 240)
    private String primaryContactName;
    @Column(name = "country_code", length = 3)
    private String countryCode;
    @Column(name = "city", length = 160)
    private String city;
    @Column(name = "legal_form", length = 80)
    private String legalForm;
    @Column(name = "branch_name", length = 240)
    private String branchName;
    @Column(length = 500)
    private String okedCode;
    @Column(length = 500)
    private String vatRegistrationCode;
    @Column(length = 80)
    private String registrationNumber;
    private LocalDate registrationDate;
    @Column(length = 500)
    private String legalAddress;
    @Column(length = 500)
    private String actualAddress;
    @Column(length = 80)
    private String phone;
    @Column(length = 160)
    private String email;
    @Column(length = 240)
    private String directorName;
    @Column(length = 240)
    private String chiefAccountantName;
    @Column(length = 240)
    private String bankName;
    @Column(length = 500)
    @Convert(converter = SensitiveStringConverter.class)
    private String bankMfo;
    @Column(length = 500)
    @Convert(converter = SensitiveStringConverter.class)
    private String bankAccount;
    @Column(name = "bank_account_hash", length = 64)
    private String bankAccountHash;
    @Column(length = 500)
    private String bankAddress;
    @Column(length = 5)
    private String category = "C";
    @Column(precision = 7, scale = 2)
    private BigDecimal rating = BigDecimal.ZERO;
    @Column(length = 500)
    private String blockedReason;
    @OneToMany(mappedBy = "supplier", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplierBankAccount> bankAccounts = new ArrayList<>();

    @PrePersist
    @PreUpdate
    void applySupplierDerivedFields() {
        normalizeBankAccountForUniqueness();
        parentSupplierId = parentSupplier == null ? parentSupplierId : parentSupplier.getId();
        ownerEmployeeId = ownerEmployee == null ? ownerEmployeeId : ownerEmployee.getId();
    }

    public void normalizeBankAccountForUniqueness() {
        bankAccount = normalizeBankAccountValue(bankAccount);
        bankAccountHash = bankAccount == null ? null : sha256(bankAccount);
    }

    public static String normalizeBankAccountValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", "");
        return normalized.isBlank() ? null : normalized;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash supplier bank account", ex);
        }
    }
}

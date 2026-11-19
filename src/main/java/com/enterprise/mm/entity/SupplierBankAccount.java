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
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_bank_accounts")
public class SupplierBankAccount extends BusinessEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "bank_name", length = 240)
    private String bankName;

    @Column(name = "bank_mfo", length = 500)
    @Convert(converter = SensitiveStringConverter.class)
    private String bankMfo;

    @Column(name = "bank_account", length = 500)
    @Convert(converter = SensitiveStringConverter.class)
    private String bankAccount;

    @Column(name = "bank_account_hash", length = 64)
    private String bankAccountHash;

    @Column(name = "bank_address", length = 500)
    private String bankAddress;

    @Column(name = "primary_account", nullable = false)
    private boolean primaryAccount;

    @PrePersist
    @PreUpdate
    void applySupplierBankAccountDerivedFields() {
        normalizeBankAccountForUniqueness();
        if (supplier != null) {
            setCompanyId(supplier.getCompanyId());
        }
        if (getCode() == null || getCode().isBlank()) {
            setCode(bankAccountHash == null ? "BANK-ACCOUNT" : "BANK-" + bankAccountHash.substring(0, 12));
        }
        if (getName() == null || getName().isBlank()) {
            setName(bankName == null || bankName.isBlank() ? "Supplier Bank Account" : bankName);
        }
        if (getStatus() == null || getStatus().isBlank()) {
            setStatus("ACTIVE");
        }
    }

    public void normalizeBankAccountForUniqueness() {
        bankAccount = Supplier.normalizeBankAccountValue(bankAccount);
        bankAccountHash = bankAccount == null ? null : Supplier.sha256(bankAccount);
    }
}

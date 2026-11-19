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
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "contract_powers_of_attorney")
public class ContractPowerOfAttorney extends BusinessEntity {
    @NotNull
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    @NotNull
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "attorney_number", length = 120)
    private String attorneyNumber;

    @Column(name = "issued_to", length = 240)
    private String issuedTo;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "amount_limit", precision = 19, scale = 4)
    private BigDecimal amountLimit = BigDecimal.ZERO;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        contractId = contract == null ? contractId : contract.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
    }
}

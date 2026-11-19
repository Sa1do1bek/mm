package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "reference_mappings")
public class ReferenceMapping extends BusinessEntity {
    @Column(name = "reference_entity_type", nullable = false, length = 80)
    private String referenceEntityType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reference_code", length = 120)
    private String referenceCode;

    @Column(name = "reference_name", length = 240)
    private String referenceName;

    @Column(name = "external_system", nullable = false, length = 80)
    private String externalSystem;

    @Column(name = "external_code", nullable = false, length = 160)
    private String externalCode;

    @Column(name = "external_name", length = 240)
    private String externalName;

    @Column(name = "accounting_area", nullable = false, length = 60)
    private String accountingArea = "MANAGEMENT";

    @Column(name = "chart_of_accounts_code", length = 80)
    private String chartOfAccountsCode;

    @Column(name = "account_code", length = 80)
    private String accountCode;

    @Column(name = "sub_account_code", length = 80)
    private String subAccountCode;

    @Column(name = "cost_center", length = 80)
    private String costCenter;

    @Column(name = "profit_center", length = 80)
    private String profitCenter;

    @Column(name = "warehouse_accounting_code", length = 80)
    private String warehouseAccountingCode;

    @Column(name = "tax_code", length = 80)
    private String taxCode;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(nullable = false)
    private int priority = 100;

    @Column(name = "default_mapping", nullable = false)
    private boolean defaultMapping;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;
}
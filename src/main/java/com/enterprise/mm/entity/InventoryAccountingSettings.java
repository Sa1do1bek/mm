package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "inventory_accounting_settings",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_inventory_accounting_settings_tenant_company",
        columnNames = {"tenant_id", "company_id"}
    )
)
public class InventoryAccountingSettings extends BusinessEntity {
    @Column(name = "default_stock_account_id")
    private UUID defaultStockAccountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_stock_account_id", insertable = false, updatable = false)
    private ChartOfAccount defaultStockAccount;

    @Column(name = "returnable_packaging_stock_account_id")
    private UUID returnablePackagingStockAccountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "returnable_packaging_stock_account_id", insertable = false, updatable = false)
    private ChartOfAccount returnablePackagingStockAccount;

    @Column(name = "default_valuation_method", nullable = false, length = 40)
    private String defaultValuationMethod = "ACTUAL_COST";

    @Column(name = "allow_warehouse_valuation_override", nullable = false)
    private boolean allowWarehouseValuationOverride = false;

    @Column(name = "allow_negative_stock", nullable = false)
    private boolean allowNegativeStock = false;

    @Column(name = "allow_zero_cost_receipt", nullable = false)
    private boolean allowZeroCostReceipt = false;

    @Column(name = "period_close_mode", nullable = false, length = 60)
    private String periodCloseMode = "CURRENT_BALANCE";

    @Column(name = "base_currency_code", nullable = false, length = 3)
    private String baseCurrencyCode = "UZS";

    @Column(name = "rounding_scale", nullable = false)
    private int roundingScale = 4;

    @Column(name = "rounding_mode", nullable = false, length = 40)
    private String roundingMode = "HALF_UP";

    @Column(name = "revaluation_posting_mode", nullable = false, length = 60)
    private String revaluationPostingMode = "VALUATION_RECORD_ONLY";

    @Column(name = "impairment_posting_mode", nullable = false, length = 60)
    private String impairmentPostingMode = "VALUATION_RECORD_ONLY";
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "data_migration_rows")
public class DataMigrationRow extends BusinessEntity {
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "row_status", nullable = false, length = 30)
    private String rowStatus;

    @Column(name = "source_key", length = 260)
    private String sourceKey;

    @Column(name = "company_code", length = 80)
    private String companyCode;

    @Column(name = "warehouse_code", length = 80)
    private String warehouseCode;

    @Column(name = "storage_location_code", length = 80)
    private String storageLocationCode;

    @Column(name = "material_code", length = 80)
    private String materialCode;

    @Column(name = "material_name", length = 240)
    private String materialName;

    @Column(length = 120)
    private String article;

    @Column(name = "uom_code", length = 80)
    private String uomCode;

    @Column(name = "batch_number", length = 120)
    private String batchNumber;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @Column(precision = 22, scale = 7)
    private BigDecimal quantity;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "total_value", precision = 19, scale = 4)
    private BigDecimal totalValue;

    @Column(name = "currency_code", length = 20)
    private String currencyCode;

    @Column(name = "account_code", length = 80)
    private String accountCode;

    @Column(name = "ikpu_code", length = 40)
    private String ikpuCode;

    @Column(name = "tnved_code", length = 40)
    private String tnvedCode;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "balance_date")
    private LocalDate balanceDate;

    @Column(name = "source_document", length = 180)
    private String sourceDocument;

    @Column(name = "error_messages", columnDefinition = "text")
    private String errorMessages;

    @Column(name = "warning_messages", columnDefinition = "text")
    private String warningMessages;
}
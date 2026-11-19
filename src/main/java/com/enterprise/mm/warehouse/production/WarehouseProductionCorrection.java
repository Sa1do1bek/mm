package com.enterprise.mm.warehouse.production;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
    name = "warehouse_production_corrections",
    uniqueConstraints = @UniqueConstraint(name = "uk_wh_prod_correction_event", columnNames = {"tenant_id", "company_id", "correction_event_id"})
)
@Getter
@Setter
public class WarehouseProductionCorrection {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "company_id", nullable = false, length = 50)
    private String companyId;

    @Column(name = "parent_event_id", nullable = false, length = 120)
    private String parentEventId;

    @Column(name = "correction_event_id", nullable = false, length = 120)
    private String correctionEventId;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "account_code", nullable = false, length = 20)
    private String accountCode = "2810";

    @Column(name = "delta_quantity", nullable = false, precision = 19, scale = 7)
    private BigDecimal deltaQuantity;

    @Column(name = "delta_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal deltaCost = BigDecimal.ZERO;

    @Column(name = "reason", length = 240)
    private String reason;

    @Column(name = "posting_receipt_id", length = 120)
    private String postingReceiptId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public WarehouseProductionCorrection() {
    }
}

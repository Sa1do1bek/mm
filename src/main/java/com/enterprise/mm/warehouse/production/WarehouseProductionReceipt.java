package com.enterprise.mm.warehouse.production;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
    name = "warehouse_production_receipts",
    uniqueConstraints = @UniqueConstraint(name = "uk_wh_prod_receipt_event", columnNames = {"tenant_id", "company_id", "event_id"})
)
@Getter
@Setter
public class WarehouseProductionReceipt {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "company_id", nullable = false, length = 50)
    private String companyId;

    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @Column(name = "event_version", nullable = false)
    private int eventVersion = 1;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "account_code", nullable = false, length = 20)
    private String accountCode = "2810";

    @Column(name = "quantity", nullable = false, precision = 19, scale = 7)
    private BigDecimal quantity;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "total_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "quality_disposition", nullable = false, length = 40)
    private String qualityDisposition = "APPROVED";

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    @Column(name = "production_order_ref", length = 120)
    private String productionOrderRef;

    @Column(name = "posting_receipt_id", length = 120)
    private String postingReceiptId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public WarehouseProductionReceipt() {
    }
}

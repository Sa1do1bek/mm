package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "vmi_stock_agreements")
public class VmiStockAgreement extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "storage_location_id")
    private UUID storageLocationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id", insertable = false, updatable = false)
    private StorageLocation storageLocation;

    @Column(name = "stock_type", nullable = false, length = 40)
    private String stockType;

    @Column(name = "supplier_sku", length = 120)
    private String supplierSku;

    @Column(name = "min_stock", precision = 19, scale = 4)
    private BigDecimal minStock = BigDecimal.ZERO;

    @Column(name = "reorder_point", precision = 19, scale = 4)
    private BigDecimal reorderPoint = BigDecimal.ZERO;

    @Column(name = "target_stock", precision = 19, scale = 4)
    private BigDecimal targetStock = BigDecimal.ZERO;

    @Column(name = "max_stock", precision = 19, scale = 4)
    private BigDecimal maxStock = BigDecimal.ZERO;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "review_cycle_days")
    private Integer reviewCycleDays;

    @Column(name = "ownership_transfer_event", length = 80)
    private String ownershipTransferEvent;

    @Column(name = "settlement_cycle", length = 80)
    private String settlementCycle;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "last_replenishment_request_at")
    private LocalDateTime lastReplenishmentRequestAt;
}

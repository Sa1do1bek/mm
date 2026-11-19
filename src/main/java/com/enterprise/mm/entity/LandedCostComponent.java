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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "landed_cost_components")
public class LandedCostComponent extends BusinessEntity {
    @Column(name = "import_delivery_id", nullable = false)
    private UUID importDeliveryId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_delivery_id", insertable = false, updatable = false)
    private ImportDelivery importDelivery;

    @Column(name = "component_type", nullable = false, length = 80)
    private String componentType;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "allocation_method", length = 40)
    private String allocationMethod = "VALUE";

    @Column(name = "supplier_id")
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "invoice_number", length = 120)
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "included_in_inventory_cost", nullable = false)
    private boolean includedInInventoryCost = true;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        importDeliveryId = importDelivery == null ? importDeliveryId : importDelivery.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
    }
}

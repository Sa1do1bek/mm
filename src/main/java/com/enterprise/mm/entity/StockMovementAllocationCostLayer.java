package com.enterprise.mm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Table(name = "stock_movement_allocation_cost_layers")
public class StockMovementAllocationCostLayer {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 80, updatable = false)
    private String tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 120, updatable = false)
    private String createdBy;

    @Column(name = "allocation_id", nullable = false, updatable = false)
    private UUID allocationId;

    @Column(name = "lineage_sequence", nullable = false, updatable = false)
    private int lineageSequence;

    @Column(name = "lineage_role", nullable = false, length = 40, updatable = false)
    private String lineageRole;

    @Column(name = "cost_layer_id", updatable = false)
    private UUID costLayerId;

    @Column(name = "parent_cost_layer_id_snapshot", updatable = false)
    private UUID parentCostLayerIdSnapshot;

    @Column(name = "source_cost_movement_id", updatable = false)
    private UUID sourceCostMovementId;

    @Column(name = "quantity_delta", nullable = false, precision = 22, scale = 7, updatable = false)
    private BigDecimal quantityDelta;

    @Column(name = "value_delta", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal valueDelta;

    @Column(name = "unit_cost_snapshot", precision = 19, scale = 6, updatable = false)
    private BigDecimal unitCostSnapshot;

    @Column(name = "currency_code_snapshot", length = 10, updatable = false)
    private String currencyCodeSnapshot;

    @Column(name = "exchange_rate_snapshot", precision = 19, scale = 6, updatable = false)
    private BigDecimal exchangeRateSnapshot;

    @PrePersist
    void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

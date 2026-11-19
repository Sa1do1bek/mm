package com.enterprise.mm.warehouse.production;

import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationLineType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "warehouse_transformation_lines")
@Getter
@Setter
public class WarehouseTransformationLine {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false)
    private WarehouseTransformationOrder order;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 20)
    private TransformationLineType lineType;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "account_code", nullable = false, length = 20)
    private String accountCode;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 7)
    private BigDecimal quantity;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "allocated_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedCost = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public WarehouseTransformationLine() {
    }
}

package com.enterprise.mm.warehouse.production;

import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationOrderStatus;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
    name = "warehouse_transformation_orders",
    uniqueConstraints = @UniqueConstraint(name = "uk_wh_transf_order", columnNames = {"tenant_id", "company_id", "order_number"})
)
@Getter
@Setter
public class WarehouseTransformationOrder {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "company_id", nullable = false, length = 50)
    private String companyId;

    @Column(name = "order_number", nullable = false, length = 120)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "transformation_type", nullable = false, length = 40)
    private TransformationType transformationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private TransformationOrderStatus status;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "processor_id")
    private UUID processorId;

    @Column(name = "total_input_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalInputCost = BigDecimal.ZERO;

    @Column(name = "total_output_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalOutputCost = BigDecimal.ZERO;

    @Column(name = "execution_date", nullable = false)
    private LocalDate executionDate;

    @Column(name = "source_reference", length = 120)
    private String sourceReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WarehouseTransformationLine> lines = new ArrayList<>();

    public WarehouseTransformationOrder() {
    }

    public void addLine(WarehouseTransformationLine line) {
        lines.add(line);
        line.setOrder(this);
        line.setOrderId(this.id);
    }
}

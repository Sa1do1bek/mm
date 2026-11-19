package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "landed_cost_allocation_lines")
public class LandedCostAllocationLine extends BusinessEntity {
    @Column(name = "allocation_document_id", nullable = false)
    private UUID allocationDocumentId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "storage_location_id")
    private UUID storageLocationId;

    @Column(name = "stock_batch_id")
    private UUID stockBatchId;

    @Column(name = "allocation_base", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocationBase = BigDecimal.ZERO;

    @Column(name = "quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount = BigDecimal.ZERO;

    @Column(name = "allocated_base_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedBaseAmount = BigDecimal.ZERO;

    @Column(name = "unit_cost_increase", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCostIncrease = BigDecimal.ZERO;
}
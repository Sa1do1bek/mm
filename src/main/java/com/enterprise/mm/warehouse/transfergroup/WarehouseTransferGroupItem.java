package com.enterprise.mm.warehouse.transfergroup;

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
@Table(name = "warehouse_transfer_group_items")
public class WarehouseTransferGroupItem extends BusinessEntity {

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "destination_warehouse_id", nullable = false)
    private UUID destinationWarehouseId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "planned_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal plannedQuantity = BigDecimal.ZERO;

    @Column(name = "child_transfer_id")
    private UUID childTransferId;

    @Column(name = "child_transfer_line_id")
    private UUID childTransferLineId;
}

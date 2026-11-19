package com.enterprise.mm.warehouse.holding;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "warehouse_holding_transfers")
public class WarehouseHoldingTransfer extends BusinessEntity {

    @Column(name = "transfer_number", nullable = false, length = 100)
    private String transferNumber;

    @Column(name = "source_holding_id", nullable = false)
    private UUID sourceHoldingId;

    @Column(name = "target_holding_id", nullable = false)
    private UUID targetHoldingId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "from_department_id")
    private UUID fromDepartmentId;

    @Column(name = "from_employee_id")
    private UUID fromEmployeeId;

    @Column(name = "to_department_id")
    private UUID toDepartmentId;

    @Column(name = "to_employee_id")
    private UUID toEmployeeId;

    @Column(name = "quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "total_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalValue = BigDecimal.ZERO;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "transferred_at", nullable = false)
    private LocalDateTime transferredAt = LocalDateTime.now();
}

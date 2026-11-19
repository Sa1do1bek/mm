package com.enterprise.mm.warehouse.holding;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "warehouse_holding_handovers")
public class WarehouseHoldingHandover extends BusinessEntity {

    @Column(name = "handover_number", nullable = false, length = 100)
    private String handoverNumber;

    @Column(name = "holding_id")
    private UUID holdingId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "employee_id")
    private UUID employeeId;

    @Column(name = "quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "total_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalValue = BigDecimal.ZERO;

    public WarehouseHoldingHandover() {
        setStatus(HandoverStatus.PENDING.name());
    }

    public HandoverStatus getHandoverStatus() {
        String s = getStatus();
        return s == null ? HandoverStatus.PENDING : HandoverStatus.valueOf(s);
    }

    public void setHandoverStatus(HandoverStatus handoverStatus) {
        setStatus(handoverStatus == null ? null : handoverStatus.name());
    }

    @Column(name = "dispatched_at", nullable = false)
    private LocalDateTime dispatchedAt = LocalDateTime.now();

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;
}

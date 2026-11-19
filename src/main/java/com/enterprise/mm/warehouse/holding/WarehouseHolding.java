package com.enterprise.mm.warehouse.holding;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "warehouse_holdings")
public class WarehouseHolding extends BusinessEntity {

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "employee_id")
    private UUID employeeId;

    @Column(name = "account_code", nullable = false, length = 40)
    private String accountCode = "014";

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId = "OWNED";

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_type", nullable = false, length = 40)
    private HoldingOriginType originType = HoldingOriginType.NATIVE_DISPATCH;

    @Column(name = "origin_reference", length = 120)
    private String originReference;

    @Column(name = "uom", nullable = false, length = 20)
    private String uom = "PCS";

    @Column(name = "pending_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal pendingQuantity = BigDecimal.ZERO;

    @Column(name = "active_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal activeQuantity = BigDecimal.ZERO;

    @Column(name = "consumed_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal consumedQuantity = BigDecimal.ZERO;

    @Column(name = "returned_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;

    @Column(name = "written_off_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal writtenOffQuantity = BigDecimal.ZERO;

    @Column(name = "transferred_in_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal transferredInQuantity = BigDecimal.ZERO;

    @Column(name = "transferred_out_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal transferredOutQuantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "total_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalValue = BigDecimal.ZERO;

    public WarehouseHolding() {
        setStatus(HoldingStatus.ACTIVE.name());
    }

    public HoldingStatus getHoldingStatus() {
        String s = getStatus();
        return s == null ? HoldingStatus.ACTIVE : HoldingStatus.valueOf(s);
    }

    public void setHoldingStatus(HoldingStatus holdingStatus) {
        setStatus(holdingStatus == null ? null : holdingStatus.name());
    }
}

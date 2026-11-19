package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.AssetHandoverStatus;
import com.enterprise.mm.enums.AssetHandoverType;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "asset_handovers")
public class AssetHandover extends BusinessEntity {
    @Column(name = "contract_id")
    private UUID contractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "handover_type", nullable = false, length = 30)
    private AssetHandoverType handoverType = AssetHandoverType.ISSUE;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_custody_type", nullable = false, length = 30)
    private FixedAssetCustodyKind fromCustodyType = FixedAssetCustodyKind.WAREHOUSE;

    @Column(name = "from_employee_id")
    private UUID fromEmployeeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_employee_id", insertable = false, updatable = false)
    private Employee fromEmployee;

    @Column(name = "from_department_id")
    private UUID fromDepartmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_department_id", insertable = false, updatable = false)
    private Department fromDepartment;

    @Column(name = "from_warehouse_id")
    private UUID fromWarehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_warehouse_id", insertable = false, updatable = false)
    private Warehouse fromWarehouse;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_custody_type", nullable = false, length = 30)
    private FixedAssetCustodyKind toCustodyType = FixedAssetCustodyKind.DEPARTMENT;

    @Column(name = "to_employee_id")
    private UUID toEmployeeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_employee_id", insertable = false, updatable = false)
    private Employee toEmployee;

    @Column(name = "to_department_id")
    private UUID toDepartmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_department_id", insertable = false, updatable = false)
    private Department toDepartment;

    @Column(name = "to_warehouse_id")
    private UUID toWarehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_warehouse_id", insertable = false, updatable = false)
    private Warehouse toWarehouse;

    @Column(name = "approval_instance_id")
    private UUID approvalInstanceId;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "exception_status", length = 30)
    private String exceptionStatus;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    public AssetHandoverStatus getHandoverStatus() {
        return getStatus() == null ? null : AssetHandoverStatus.valueOf(getStatus());
    }

    public void setHandoverStatus(AssetHandoverStatus status) {
        setStatus(status == null ? null : status.name());
    }
}

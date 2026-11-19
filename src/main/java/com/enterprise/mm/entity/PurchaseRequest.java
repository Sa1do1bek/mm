package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.RequestType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "purchase_requests")
public class PurchaseRequest extends BusinessEntity {
    @Column(name = "department_id")
    private UUID departmentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    private Department department;
    @Column(name = "demand_plan_id")
    private UUID demandPlanId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demand_plan_id", insertable = false, updatable = false)
    private DemandPlan demandPlan;
    @Column(length = 120)
    private String requestedBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", length = 20)
    private RequestType requestType = RequestType.GOODS;
    private LocalDate requiredDate;
    @Column(precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;
    @Column(length = 3)
    private String currencyCode = "UZS";
    @Column(name = "warehouse_id")
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        departmentId = department == null ? departmentId : department.getId();
        demandPlanId = demandPlan == null ? demandPlanId : demandPlan.getId();
    }
}

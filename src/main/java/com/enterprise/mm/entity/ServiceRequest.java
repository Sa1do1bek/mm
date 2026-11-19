package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "service_requests")
public class ServiceRequest extends BusinessEntity {
    @Column(name = "request_number", length = 120)
    private String number;

    @Column(length = 240)
    private String title;

    @Column(length = 120)
    private String category;

    @Column(length = 40)
    private String urgency = "NORMAL";

    @Column(name = "department_id")
    private UUID departmentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    private Department department;

    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsible_user_id", insertable = false, updatable = false)
    private User responsibleUser;

    @Column(name = "supplier_id")
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "contract_id")
    private UUID contractId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "budget_item_id")
    private UUID budgetItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_item_id", insertable = false, updatable = false)
    private BudgetItem budgetItem;

    @Column(name = "cost_center_id")
    private UUID costCenterId;

    @Column(precision = 19, scale = 4)
    private BigDecimal estimatedAmount = BigDecimal.ZERO;

    @Column(precision = 19, scale = 4)
    private BigDecimal approvedAmount = BigDecimal.ZERO;

    @Column(precision = 19, scale = 4)
    private BigDecimal actualAmount = BigDecimal.ZERO;

    @Column(name = "acceptance_mode", nullable = false, length = 20)
    private String acceptanceMode = "AMOUNT";

    @Column(name = "ordered_amount", precision = 19, scale = 4)
    private BigDecimal orderedAmount;

    @Column(name = "accepted_amount", precision = 19, scale = 4)
    private BigDecimal acceptedAmount;

    @Column(name = "rejected_amount", precision = 19, scale = 4)
    private BigDecimal rejectedAmount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "ordered_quantity", precision = 22, scale = 7)
    private BigDecimal orderedQuantity;

    @Column(name = "accepted_quantity", precision = 22, scale = 7)
    private BigDecimal acceptedQuantity;

    @Column(name = "rejected_quantity", precision = 22, scale = 7)
    private BigDecimal rejectedQuantity;

    @Column(name = "uom_code", length = 40)
    private String uomCode;

    @Column(name = "accepted_volume", precision = 19, scale = 4)
    private BigDecimal acceptedVolume = BigDecimal.ZERO;

    @Column(name = "rejected_volume", precision = 19, scale = 4)
    private BigDecimal rejectedVolume = BigDecimal.ZERO;

    @Column(name = "acceptance_remarks", columnDefinition = "text")
    private String acceptanceRemarks;

    @Column(name = "budget_reserved_amount", precision = 19, scale = 4)
    private BigDecimal budgetReservedAmount = BigDecimal.ZERO;

    @Column(name = "budget_reserved_at")
    private LocalDateTime budgetReservedAt;

    @Column(name = "budget_released_at")
    private LocalDateTime budgetReleasedAt;

    @Column(name = "budget_consumed_at")
    private LocalDateTime budgetConsumedAt;

    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;

    public BigDecimal getRemainingAmount() {
        return remaining(orderedAmount, acceptedAmount, rejectedAmount);
    }

    public BigDecimal getRemainingQuantity() {
        return remaining(orderedQuantity, acceptedQuantity, rejectedQuantity);
    }

    private BigDecimal remaining(BigDecimal ordered, BigDecimal accepted, BigDecimal rejected) {
        if (ordered == null) {
            return null;
        }
        return ordered
            .subtract(accepted == null ? BigDecimal.ZERO : accepted)
            .subtract(rejected == null ? BigDecimal.ZERO : rejected)
            .max(BigDecimal.ZERO);
    }
}

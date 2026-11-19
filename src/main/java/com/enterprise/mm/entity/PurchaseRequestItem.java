package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
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
@Table(name = "purchase_request_items")
public class PurchaseRequestItem extends BusinessEntity {
    @Column(name = "purchase_request_id", nullable = false)
    private UUID purchaseRequestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_id", insertable = false, updatable = false)
    private PurchaseRequest purchaseRequest;
    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 20)
    private ProcurementLineType lineType = ProcurementLineType.MATERIAL;
    @Column(name = "material_id")
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Enumerated(EnumType.STRING)
    @Column(name = "material_type_snapshot", length = 30)
    private MaterialType materialTypeSnapshot;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal estimatedPrice = BigDecimal.ZERO;
    // Source-owned date snapshot for demand conversion; legacy lines use their header.
    @Column(name = "required_date")
    private LocalDate requiredDate;

    public LocalDate fulfillmentDate(PurchaseRequest parent) {
        return requiredDate != null ? requiredDate : parent == null ? null : parent.getRequiredDate();
    }
    @Column(name = "service_request_id")
    private UUID serviceRequestId;
    @Column(name = "service_description", columnDefinition = "text")
    private String serviceDescription;
    @Column(name = "service_category", length = 120)
    private String serviceCategory;
    @Column(length = 80)
    private String unit;
    @Column(length = 120)
    private String period;
    @Column(length = 120)
    private String stage;
    @Column(name = "budget_item_id")
    private UUID budgetItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_item_id", insertable = false, updatable = false)
    private BudgetItem budgetItem;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        purchaseRequestId = purchaseRequest == null ? purchaseRequestId : purchaseRequest.getId();
        materialId = material == null ? materialId : material.getId();
        budgetItemId = budgetItem == null ? budgetItemId : budgetItem.getId();
    }
}

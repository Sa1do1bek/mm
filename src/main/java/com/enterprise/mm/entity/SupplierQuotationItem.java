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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_quotation_items")
public class SupplierQuotationItem extends BusinessEntity {
    @Column(name = "quotation_id", nullable = false)
    private UUID quotationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id", insertable = false, updatable = false)
    private SupplierQuotation quotation;
    @Column(name = "rfq_item_id")
    private UUID rfqItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_item_id", insertable = false, updatable = false)
    private RFQItem rfqItem;
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
    private BigDecimal unitPrice = BigDecimal.ZERO;
    private Integer deliveryDays = 0;
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
    @Column(name = "pricing_model", length = 40)
    private String pricingModel;
    @Column(name = "budget_item_id")
    private UUID budgetItemId;
    @Column(name = "no_bid", nullable = false)
    private boolean noBid;
    @Column(name = "no_bid_reason", columnDefinition = "text")
    private String noBidReason;
    @Column(name = "tax_rate", precision = 7, scale = 4)
    private BigDecimal taxRate;
    @Column(name = "supplier_note", columnDefinition = "text")
    private String supplierNote;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        quotationId = quotation == null ? quotationId : quotation.getId();
        rfqItemId = rfqItem == null ? rfqItemId : rfqItem.getId();
        materialId = material == null ? materialId : material.getId();
    }
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.RfqAggregationMode;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "rfqs")
public class RFQ extends BusinessEntity {
    @Column(name = "purchase_request_id")
    private UUID purchaseRequestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_id", insertable = false, updatable = false)
    private PurchaseRequest purchaseRequest;
    private LocalDate dueDate;
    @Column(name = "tender_procedure_type", length = 50)
    private String tenderProcedureType = "REQUEST_FOR_QUOTATION";
    @Column(name = "purpose", nullable = false, length = 40)
    private String purpose = "PROCUREMENT";
    @Column(name = "procurement_procedure_id")
    private UUID procurementProcedureId;
    @Column(name = "source_rfq_id")
    private UUID sourceRfqId;
    @Column(name = "price_basis_calculation_id")
    private UUID priceBasisCalculationId;
    @Column(name = "selection_rule", length = 50)
    private String selectionRule = "LOWEST_PRICE";
    private Integer minQuotationsRequired = 1;
    @Column(name = "aggregated_tender", nullable = false)
    private boolean aggregatedTender = false;
    @Enumerated(EnumType.STRING)
    @Column(name = "aggregation_mode", length = 30)
    private RfqAggregationMode aggregationMode;
    @Column(name = "material_group_id")
    private UUID materialGroupId;
    @Column(name = "source_purchase_request_count", nullable = false)
    private Integer sourcePurchaseRequestCount = 0;
    @Column(name = "source_item_count", nullable = false)
    private Integer sourceItemCount = 0;
    @Column(name = "currency_code", length = 3)
    private String currencyCode;
    @Column(name = "responsible_buyer", length = 120)
    private String responsibleBuyer;
    @Column(name = "response_instructions", columnDefinition = "text")
    private String responseInstructions;
    @Column(name = "package_language", nullable = false, length = 5)
    private String packageLanguage = "ru";
    @Column(name = "active_published_version_id")
    private UUID activePublishedVersionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_published_version_id", insertable = false, updatable = false)
    private RfqPublishedVersion activePublishedVersion;
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    @Column(name = "published_by", length = 120)
    private String publishedBy;
    @Column(name = "closed_at")
    private LocalDateTime closedAt;
    @Column(name = "pending_amendment_reason", columnDefinition = "text")
    private String pendingAmendmentReason;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        purchaseRequestId = purchaseRequest == null ? purchaseRequestId : purchaseRequest.getId();
    }
}

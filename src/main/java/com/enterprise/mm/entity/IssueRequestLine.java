package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "issue_request_lines")
public class IssueRequestLine extends BusinessEntity {
    @Column(name = "issue_request_id", nullable = false)
    private UUID issueRequestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_request_id", insertable = false, updatable = false)
    private IssueRequest issueRequest;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "requested_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal requestedQuantity = BigDecimal.ZERO;

    @Column(name = "approved_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal approvedQuantity = BigDecimal.ZERO;

    @Column(name = "issued_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal issuedQuantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "line_comment", columnDefinition = "text")
    private String lineComment;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "storage_location_id")
    private UUID storageLocationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id", insertable = false, updatable = false)
    private StorageLocation storageLocation;

    @Column(name = "stock_batch_id")
    private UUID stockBatchId;

    @Column(name = "stock_movement_id")
    private UUID stockMovementId;

    @Column(name = "source_demand_plan_item_id")
    private UUID sourceDemandPlanItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_demand_plan_item_id", insertable = false, updatable = false)
    private DemandPlanItem sourceDemandPlanItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_batch_id", insertable = false, updatable = false)
    private StockBatch stockBatch;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        issueRequestId = issueRequest == null ? issueRequestId : issueRequest.getId();
        materialId = material == null ? materialId : material.getId();
        storageLocationId = storageLocation == null ? storageLocationId : storageLocation.getId();
        stockBatchId = stockBatch == null ? stockBatchId : stockBatch.getId();
    }
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "transfer_document_lines")
public class TransferDocumentLine extends BusinessEntity {
    @Column(name = "cancelled_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal cancelledQuantity = BigDecimal.ZERO;

    @Column(name = "transfer_document_id", nullable = false)
    private UUID transferDocumentId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "planned_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal plannedQuantity = BigDecimal.ZERO;

    @Column(name = "dispatched_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal dispatchedQuantity = BigDecimal.ZERO;

    @Column(name = "in_transit_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal inTransitQuantity = BigDecimal.ZERO;

    @Column(name = "received_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Column(name = "damaged_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal damagedQuantity = BigDecimal.ZERO;

    @Column(name = "missing_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal missingQuantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", precision = 19, scale = 6)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "source_stock_batch_id")
    private UUID sourceStockBatchId;

    @Column(name = "destination_stock_batch_id")
    private UUID destinationStockBatchId;

    @Column(name = "source_storage_location_id")
    private UUID sourceStorageLocationId;

    @Column(name = "destination_storage_location_id")
    private UUID destinationStorageLocationId;

    @Column(name = "shortage_reason", columnDefinition = "text")
    private String shortageReason;

    @Column(name = "discrepancy_reason", columnDefinition = "text")
    private String discrepancyReason;

    @Column(name = "discrepancy_evidence", columnDefinition = "text")
    private String discrepancyEvidence;
}

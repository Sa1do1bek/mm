package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MaterialReturnCondition;
import com.enterprise.mm.enums.ReturnStockStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "return_document_lines")
public class MaterialReturnLine extends BusinessEntity {
    @Column(name = "return_document_id", nullable = false)
    private UUID returnDocumentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_document_id", insertable = false, updatable = false)
    private MaterialReturnDocument returnDocument;

    @Column(name = "issue_request_line_id")
    private UUID issueRequestLineId;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "owner_id", length = 64)
    private String ownerId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "original_stock_movement_id")
    private UUID originalStockMovementId;

    @Column(name = "stock_batch_id")
    private UUID stockBatchId;

    @Column(name = "storage_location_id")
    private UUID storageLocationId;

    @Column(name = "issued_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal issuedQuantity = BigDecimal.ZERO;

    @Column(name = "previously_returned_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal previouslyReturnedQuantity = BigDecimal.ZERO;

    @Column(name = "return_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal returnQuantity = BigDecimal.ZERO;

    @Column(name = "accepted_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;

    @Column(name = "rejected_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_condition", nullable = false, length = 40)
    private MaterialReturnCondition condition;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_stock_status", length = 40)
    private ReturnStockStatus targetStockStatus;

    @Column(name = "original_unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal originalUnitCost = BigDecimal.ZERO;

    @Column(name = "line_comment", columnDefinition = "text")
    private String lineComment;

    @Column(name = "inspection_note", columnDefinition = "text")
    private String inspectionNote;
}

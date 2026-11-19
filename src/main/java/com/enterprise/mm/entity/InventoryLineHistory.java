package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.InventoryLineStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "inventory_line_history")
public class InventoryLineHistory extends BusinessEntity {
    @Column(name = "inventory_item_id", nullable = false)
    private UUID inventoryItemId;
    private Long oldCountRevision;
    private Long newCountRevision;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition="jsonb", nullable=false)
    private java.util.Map<String,Object> reviewEvidence = new java.util.LinkedHashMap<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_item_id", insertable = false, updatable = false)
    private InventoryItem inventoryItem;

    @Column(name = "inventory_document_id", nullable = false)
    private UUID inventoryDocumentId;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "old_counted_quantity", precision = 22, scale = 7)
    private BigDecimal oldCountedQuantity;

    @Column(name = "new_counted_quantity", precision = 22, scale = 7)
    private BigDecimal newCountedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 40)
    private InventoryLineStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 40)
    private InventoryLineStatus newStatus;

    @Column(name = "old_difference_quantity", precision = 22, scale = 7)
    private BigDecimal oldDifferenceQuantity;

    @Column(name = "new_difference_quantity", precision = 22, scale = 7)
    private BigDecimal newDifferenceQuantity;

    @Column(length = 1000)
    private String reason;

    @Column(name = "changed_by", nullable = false, length = 120)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}

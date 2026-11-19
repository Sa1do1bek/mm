package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "goods_receipt_units")
public class GoodsReceiptUnit extends BusinessEntity {
    @Column(name = "goods_receipt_line_id", nullable = false, updatable = false)
    private UUID goodsReceiptLineId;
    @Column(name = "goods_receipt_batch_id", updatable = false)
    private UUID goodsReceiptBatchId;
    @Column(name = "manufacturer_serial_number", length = 160, updatable = false)
    private String manufacturerSerialNumber;
    @Column(name = "normalized_serial_number", length = 160, updatable = false)
    private String normalizedSerialNumber;
    @Column(name = "condition", length = 80, updatable = false)
    private String condition;
    @Column(name = "quality_decision", length = 40, nullable = false, updatable = false)
    private String qualityDecision;
    @Column(name = "notes", columnDefinition = "text", updatable = false)
    private String notes;
    @Column(name = "material_unit_id")
    private UUID materialUnitId;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Immutable source-to-target allocation shared by procurement documents. */
@Getter
@Setter
@Entity
@Table(name = "procurement_line_allocations")
public class ProcurementLineAllocation extends BusinessEntity {
    @Column(name = "source_document_type", nullable = false, length = 40)
    private String sourceDocumentType;
    @Column(name = "source_document_id", nullable = false)
    private UUID sourceDocumentId;
    @Column(name = "source_line_id", nullable = false)
    private UUID sourceLineId;
    @Column(name = "target_document_type", nullable = false, length = 40)
    private String targetDocumentType;
    @Column(name = "target_document_id", nullable = false)
    private UUID targetDocumentId;
    @Column(name = "target_line_id", nullable = false)
    private UUID targetLineId;
    @Column(name = "allocated_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal allocatedQuantity = BigDecimal.ZERO;
    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount = BigDecimal.ZERO;
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";
    @Column(name = "supplier_id")
    private UUID supplierId;
    @Column(name = "cancelled_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal cancelledQuantity = BigDecimal.ZERO;
    @Column(name = "cancelled_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal cancelledAmount = BigDecimal.ZERO;
    @Column(name = "reversed_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal reversedQuantity = BigDecimal.ZERO;
    @Column(name = "reversed_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal reversedAmount = BigDecimal.ZERO;
    @Column(name = "idempotency_key", nullable = false, length = 220)
    private String idempotencyKey;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.WorkInspectionResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "work_completion_acts")
public class WorkCompletionAct extends BusinessEntity {
    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "purchase_order_item_id", nullable = false)
    private UUID purchaseOrderItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_item_id", insertable = false, updatable = false)
    private PurchaseOrderItem purchaseOrderItem;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    @Column(name = "work_location", length = 240)
    private String workLocation;

    @Column(length = 120)
    private String milestone;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "completed_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal completedQuantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "inspection_result", length = 40)
    private WorkInspectionResult inspectionResult;

    private LocalDateTime acceptedAt;

    @Column(name = "accepted_by", length = 120)
    private String acceptedBy;

    @Column(name = "commission_notes", columnDefinition = "text")
    private String commissionNotes;

    @Column(columnDefinition = "text")
    private String remarks;

    @Column(name = "document_reviewed_at")
    private LocalDateTime documentReviewedAt;

    @Column(name = "finance_reviewed_at")
    private LocalDateTime financeReviewedAt;

    @Column(name = "sent_to_payment_at")
    private LocalDateTime sentToPaymentAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "contract_consumed_at")
    private LocalDateTime contractConsumedAt;

    @Column(name = "payment_reference", length = 120)
    private String paymentReference;
}

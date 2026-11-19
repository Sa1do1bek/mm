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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "service_acceptance_acts")
public class ServiceAcceptanceAct extends BusinessEntity {
    @Column(name = "service_request_id")
    private UUID serviceRequestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_request_id", insertable = false, updatable = false)
    private ServiceRequest serviceRequest;

    @Column(name = "service_order_id", nullable = false)
    private UUID serviceOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_order_id", insertable = false, updatable = false)
    private ServiceOrder serviceOrder;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "contract_id")
    private UUID contractId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    private LocalDateTime acceptedAt;
    @Column(length = 120)
    private String acceptedBy;
    @Column(precision = 19, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal vatAmount = BigDecimal.ZERO;
    @Column(precision = 7, scale = 2)
    private BigDecimal vatRate = BigDecimal.ZERO;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    @Column(length = 120)
    private String stage;
    @Column(name = "document_reviewed_at")
    private LocalDateTime documentReviewedAt;
    @Column(name = "finance_reviewed_at")
    private LocalDateTime financeReviewedAt;
    @Column(name = "sent_to_payment_at")
    private LocalDateTime sentToPaymentAt;
    @Column(name = "paid_at")
    private LocalDateTime paidAt;
    @Column(name = "budget_consumed_at")
    private LocalDateTime budgetConsumedAt;
    @Column(name = "contract_consumed_at")
    private LocalDateTime contractConsumedAt;
    @Column(name = "payment_reference", length = 120)
    private String paymentReference;
    @Column(columnDefinition = "text")
    private String commissionNotes;
    @Column(columnDefinition = "text")
    private String remarks;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        serviceRequestId = serviceRequest == null ? serviceRequestId : serviceRequest.getId();
        serviceOrderId = serviceOrder == null ? serviceOrderId : serviceOrder.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        contractId = contract == null ? contractId : contract.getId();
    }
}

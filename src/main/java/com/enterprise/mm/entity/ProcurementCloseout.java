package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ProcurementCloseoutLiabilityDisposition;
import com.enterprise.mm.enums.ProcurementCloseoutReadinessStatus;
import com.enterprise.mm.enums.ProcurementCloseoutStatus;
import com.enterprise.mm.enums.ProcurementCloseoutType;
import com.enterprise.mm.enums.ProcurementFinancialClosePolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "procurement_closeouts")
public class ProcurementCloseout extends BusinessEntity {
    @Column(name = "procurement_procedure_id", nullable = false)
    private UUID procurementProcedureId;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "purchase_request_id")
    private UUID purchaseRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "closeout_status", nullable = false, length = 40)
    private ProcurementCloseoutStatus closeoutStatus = ProcurementCloseoutStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "closeout_type", nullable = false, length = 40)
    private ProcurementCloseoutType closeoutType = ProcurementCloseoutType.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "financial_close_policy", nullable = false, length = 60)
    private ProcurementFinancialClosePolicy financialClosePolicy = ProcurementFinancialClosePolicy.PAYMENT_REQUIRED;

    @Enumerated(EnumType.STRING)
    @Column(name = "readiness_status", nullable = false, length = 40)
    private ProcurementCloseoutReadinessStatus readinessStatus = ProcurementCloseoutReadinessStatus.BLOCKED;

    @Column(name = "readiness_checked_at")
    private LocalDateTime readinessCheckedAt;

    @Column(name = "readiness_checked_by", length = 120)
    private String readinessCheckedBy;

    @Column(name = "blocking_reason_count", nullable = false)
    private int blockingReasonCount;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by", length = 120)
    private String submittedBy;

    @Column(name = "approval_instance_id")
    private UUID approvalInstanceId;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by", length = 120)
    private String approvedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 120)
    private String closedBy;

    @Column(name = "closeout_note", columnDefinition = "text")
    private String closeoutNote;

    @Column(name = "close_reason", columnDefinition = "text")
    private String closeReason;

    @Column(name = "early_close_reason", columnDefinition = "text")
    private String earlyCloseReason;

    @Column(name = "cancellation_reason", columnDefinition = "text")
    private String cancellationReason;

    @Column(name = "reopen_reason", columnDefinition = "text")
    private String reopenReason;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopened_by", length = 120)
    private String reopenedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "liability_disposition", nullable = false, length = 60)
    private ProcurementCloseoutLiabilityDisposition liabilityDisposition = ProcurementCloseoutLiabilityDisposition.NONE;

    @Column(name = "expected_liability_amount", precision = 19, scale = 4)
    private java.math.BigDecimal expectedLiabilityAmount = java.math.BigDecimal.ZERO;

    @Column(name = "unresolved_liability_amount", precision = 19, scale = 4)
    private java.math.BigDecimal unresolvedLiabilityAmount = java.math.BigDecimal.ZERO;

    @Column(name = "readiness_snapshot", columnDefinition = "text")
    private String readinessSnapshot;
}

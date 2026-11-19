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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One append-only response revision to a {@link SupplierPoDispatch}. Submitted
 * responses are immutable -- corrections always create the next revision via
 * "replace", never an in-place edit of a submitted row.
 */
@Getter
@Setter
@Entity
@Table(name = "supplier_po_responses")
public class SupplierPoResponse extends BusinessEntity {
    @Column(name = "dispatch_id", nullable = false)
    private UUID dispatchId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispatch_id", insertable = false, updatable = false)
    private SupplierPoDispatch dispatch;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "portal_account_id")
    private UUID portalAccountId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portal_account_id", insertable = false, updatable = false)
    private SupplierPortalAccount portalAccount;

    @Column(name = "response_revision", nullable = false)
    private int responseRevision = 1;

    @Column(name = "response_type", length = 50)
    private String responseType;

    @Column(name = "response_status", length = 50)
    private String responseStatus;

    @Column(name = "supplier_comment", columnDefinition = "text")
    private String supplierComment;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "buyer_decision_by", length = 120)
    private String buyerDecisionBy;

    @Column(name = "buyer_decision_at")
    private LocalDateTime buyerDecisionAt;

    @Column(name = "buyer_decision_comment", columnDefinition = "text")
    private String buyerDecisionComment;

    @Column(name = "superseded_response_id")
    private UUID supersededResponseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_response_id", insertable = false, updatable = false)
    private SupplierPoResponse supersededResponse;

    @Column(name = "reconfirmation_origin_commitment_id")
    private UUID reconfirmationOriginCommitmentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reconfirmation_origin_commitment_id", insertable = false, updatable = false)
    private SupplierPoCommitment reconfirmationOriginCommitment;

    @Column(name = "reconfirmation_reason", columnDefinition = "text")
    private String reconfirmationReason;

    @Column(name = "reconfirmation_requested_by", length = 120)
    private String reconfirmationRequestedBy;

    @Column(name = "reconfirmation_requested_at")
    private LocalDateTime reconfirmationRequestedAt;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        dispatchId = dispatch == null ? dispatchId : dispatch.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
        portalAccountId = portalAccount == null ? portalAccountId : portalAccount.getId();
        supersededResponseId = supersededResponse == null ? supersededResponseId : supersededResponse.getId();
        reconfirmationOriginCommitmentId = reconfirmationOriginCommitment == null
            ? reconfirmationOriginCommitmentId
            : reconfirmationOriginCommitment.getId();
    }
}

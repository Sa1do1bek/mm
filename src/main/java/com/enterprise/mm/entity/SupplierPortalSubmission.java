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

@Getter
@Setter
@Entity
@Table(name = "supplier_portal_submissions")
public class SupplierPortalSubmission extends BusinessEntity {
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

    @Column(name = "submission_type", length = 80)
    private String submissionType;

    @Column(name = "reference_entity_type", length = 80)
    private String referenceEntityType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "payload_json", columnDefinition = "text")
    private String payloadJson;

    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 120)
    private String reviewedBy;

    @Column(name = "review_comment", length = 1000)
    private String reviewComment;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
        portalAccountId = portalAccount == null ? portalAccountId : portalAccount.getId();
    }
}

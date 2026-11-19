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
@Table(name = "supplier_kyc_checks")
public class SupplierKycCheck extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "check_type", nullable = false, length = 80)
    private String checkType;

    @Column(length = 120)
    private String provider;

    @Column(name = "external_check_id", length = 160)
    private String externalCheckId;

    @Column(name = "risk_level", nullable = false, length = 40)
    private String riskLevel = "LOW";

    @Column(precision = 7, scale = 2)
    private BigDecimal score = BigDecimal.ZERO;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "next_review_date")
    private LocalDate nextReviewDate;

    @Column(name = "evidence_attachment_id")
    private UUID evidenceAttachmentId;

    @Column(columnDefinition = "text")
    private String findings;

    @Column(name = "remediation_required", nullable = false)
    private boolean remediationRequired;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
    }
}

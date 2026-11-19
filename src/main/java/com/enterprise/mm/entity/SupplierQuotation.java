package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.QuotationSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "supplier_quotations")
public class SupplierQuotation extends BusinessEntity {
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;
    @Column(precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;
    @Column(length = 3)
    private String currencyCode = "UZS";
    private LocalDateTime submittedAt;
    @Column(name = "selected_flag", nullable = false)
    private boolean selected;
    @Column(precision = 7, scale = 2)
    private BigDecimal technicalScore = BigDecimal.ZERO;
    @Column(precision = 7, scale = 2)
    private BigDecimal deliveryScore = BigDecimal.ZERO;
    @Column(precision = 7, scale = 2)
    private BigDecimal priceScore = BigDecimal.ZERO;
    @Column(precision = 7, scale = 2)
    private BigDecimal totalScore = BigDecimal.ZERO;
    @Column(length = 500)
    private String selectionReason;
    @Column(name = "thread_id")
    private UUID threadId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id", insertable = false, updatable = false)
    private SupplierQuotationThread thread;
    @Column(name = "published_version_id")
    private UUID publishedVersionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_version_id", insertable = false, updatable = false)
    private RfqPublishedVersion publishedVersion;
    @Column(name = "revision_number")
    private Integer revisionNumber;
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 30)
    private QuotationSource source;
    @Column(name = "received_at")
    private LocalDateTime receivedAt;
    @Column(name = "recorded_by", length = 120)
    private String recordedBy;
    @Column(name = "current_revision", nullable = false)
    private boolean currentRevision = true;
    @Column(name = "supersedes_quotation_id")
    private UUID supersedesQuotationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_quotation_id", insertable = false, updatable = false)
    private SupplierQuotation supersedesQuotation;
    @Column(name = "supersession_reason", columnDefinition = "text")
    private String supersessionReason;
    @Column(name = "original_attachment_id")
    private UUID originalAttachmentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_attachment_id", insertable = false, updatable = false)
    private Attachment originalAttachment;
    @Column(name = "payment_terms", columnDefinition = "text")
    private String paymentTerms;
    @Column(name = "delivery_terms", columnDefinition = "text")
    private String deliveryTerms;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "tax_included")
    private Boolean taxIncluded;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        rfqId = rfq == null ? rfqId : rfq.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
    }
}

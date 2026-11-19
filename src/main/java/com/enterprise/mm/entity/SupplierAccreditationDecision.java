package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.converter.UuidListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_accreditation_decisions")
public class SupplierAccreditationDecision extends BusinessEntity {
    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", insertable = false, updatable = false)
    private SupplierAccreditationApplication application;

    @Column(name = "decision", length = 80, nullable = false)
    private String decision;

    @Column(name = "approved_scope", length = 500)
    private String approvedScope;

    @Column(name = "allowed_material_group_ids", columnDefinition = "text")
    @Convert(converter = UuidListConverter.class)
    private List<UUID> allowedMaterialGroupIds;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "conditions", length = 1000)
    private String conditions;

    @Column(name = "commission_protocol_no", length = 120)
    private String commissionProtocolNo;

    /** Initiator's comment captured at proposal time (task 3). The approver's own comment lands
     * on {@link SupplierAccreditationApplication#getReviewComment()} at finalization time. */
    @Column(name = "review_comment", length = 1000)
    private String reviewComment;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        applicationId = application == null ? applicationId : application.getId();
    }
}

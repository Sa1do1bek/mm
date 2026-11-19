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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_accreditation_applications")
public class SupplierAccreditationApplication extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "accreditation_type", length = 80, nullable = false)
    private String accreditationType;

    @Column(name = "requested_scope", length = 500)
    private String requestedScope;

    @Column(name = "requested_material_group_ids", columnDefinition = "text")
    @Convert(converter = UuidListConverter.class)
    private List<UUID> requestedMaterialGroupIds;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "review_started_at")
    private LocalDateTime reviewStartedAt;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "risk_score", precision = 7, scale = 2)
    private BigDecimal riskScore = BigDecimal.ZERO;

    @Column(name = "review_comment", length = 1000)
    private String reviewComment;

    @Column(name = "compliance_recommendation", length = 50)
    private String complianceRecommendation;

    @Column(name = "compliance_snapshot_json", columnDefinition = "text")
    private String complianceSnapshotJson;

    @Column(name = "parent_application_id")
    private UUID parentApplicationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_application_id", insertable = false, updatable = false)
    private SupplierAccreditationApplication parentApplication;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
        parentApplicationId = parentApplication == null ? parentApplicationId : parentApplication.getId();
    }
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.QmsAssessmentStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "qms_assessments")
public class SupplierAssessment extends BusinessEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", insertable = false, updatable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "assessment_date", nullable = false)
    private LocalDate assessmentDate;

    @Column(name = "total_weighted_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal totalWeightedScore = BigDecimal.ZERO;

    @Column(name = "assessed_by")
    private UUID assessedBy;

    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplierAssessmentDetail> details = new ArrayList<>();

    public QmsAssessmentStatus getAssessmentStatus() {
        if (getStatus() == null || getStatus().isBlank()) {
            return QmsAssessmentStatus.DRAFT;
        }
        return QmsAssessmentStatus.valueOf(getStatus());
    }

    public void setAssessmentStatus(QmsAssessmentStatus status) {
        setStatus(status == null ? null : status.name());
    }
}

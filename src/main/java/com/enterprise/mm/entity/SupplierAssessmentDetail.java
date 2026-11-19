package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "qms_assessment_details",
    uniqueConstraints = @UniqueConstraint(name = "uk_qms_assessment_details_assessment_criteria", columnNames = {"assessment_id", "criteria_id"})
)
public class SupplierAssessmentDetail extends BusinessEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assessment_id", nullable = false)
    private SupplierAssessment assessment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "criteria_id", nullable = false)
    private Criteria criteria;

    @Column(name = "given_score", nullable = false, precision = 7, scale = 2)
    private BigDecimal givenScore = BigDecimal.ZERO;

    @Column(name = "comment_text", columnDefinition = "text")
    private String comment;
}

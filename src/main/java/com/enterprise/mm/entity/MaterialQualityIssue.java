package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_quality_issues")
public class MaterialQualityIssue extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "issue_type", nullable = false, length = 80)
    private String issueType;

    @Column(nullable = false, length = 40)
    private String severity;

    @Column(name = "field_name", length = 120)
    private String fieldName;

    @Column(name = "assigned_to", length = 120)
    private String assignedTo;

    @Column(name = "resolution_comment", columnDefinition = "text")
    private String resolutionComment;

    @Column(name = "evidence_reference", length = 500)
    private String evidenceReference;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}

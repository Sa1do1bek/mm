package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_duplicate_candidates")
public class MaterialDuplicateCandidate extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "candidate_material_id", nullable = false)
    private UUID candidateMaterialId;

    @Column(name = "similarity_score", precision = 8, scale = 4)
    private BigDecimal similarityScore;

    @Column(length = 240)
    private String reason;

    @Column(name = "ai_source_job_id")
    private UUID aiSourceJobId;

    @Column(name = "ai_evidence_json", columnDefinition = "text")
    private String aiEvidenceJson;

    @Column(name = "decision_comment", columnDefinition = "text")
    private String decisionComment;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ManagementOpportunityEvidenceSourceEntityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "management_opportunity_evidence")
public class ManagementOpportunityEvidence extends BusinessEntity {
    @Column(name = "opportunity_id", nullable = false, updatable = false)
    private UUID opportunityId;

    @Column(name = "evidence_type", nullable = false, length = 80, updatable = false)
    private String evidenceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_entity_type", nullable = false, length = 80, updatable = false)
    private ManagementOpportunityEvidenceSourceEntityType sourceEntityType;

    @Column(name = "source_entity_id", updatable = false)
    private UUID sourceEntityId;

    @Column(name = "captured_snapshot", nullable = false, columnDefinition = "text", updatable = false)
    private String capturedSnapshot;
}

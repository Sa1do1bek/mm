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
@Table(name = "laboratory_tests")
public class LaboratoryTest extends BusinessEntity {
    @Column(name = "quality_inspection_id", nullable = false)
    private UUID qualityInspectionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_inspection_id", insertable = false, updatable = false)
    private QualityInspection qualityInspection;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "sample_number", length = 120)
    private String sampleNumber;

    @Column(name = "test_type", nullable = false, length = 120)
    private String testType;

    @Column(name = "test_method", length = 240)
    private String testMethod;

    @Column(name = "specification", length = 1000)
    private String specification;

    @Column(name = "measured_value", length = 240)
    private String measuredValue;

    @Column(name = "result", columnDefinition = "text")
    private String result;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "lab_technician_employee_id")
    private UUID labTechnicianEmployeeId;

    @Column(name = "protocol_attachment_id")
    private UUID protocolAttachmentId;

    @Column(name = "lims_sample_id", length = 160)
    private String limsSampleId;

    @Column(name = "lims_status", length = 80)
    private String limsStatus;

    @Column(name = "lims_requested_at")
    private LocalDateTime limsRequestedAt;

    @Column(name = "lims_result_received_at")
    private LocalDateTime limsResultReceivedAt;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        qualityInspectionId = qualityInspection == null ? qualityInspectionId : qualityInspection.getId();
        materialId = material == null ? materialId : material.getId();
    }
}

package com.enterprise.mm.entity;

import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Table(name = "ai_analysis_jobs")
public class AiAnalysisJob {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 80)
    private String tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_type", nullable = false, updatable = false, length = 60)
    private AiAnalysisType analysisType;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AiAnalysisJobStatus status;

    @Column(name = "requested_by", nullable = false, updatable = false, length = 120)
    private String requestedBy;

    @Column(name = "requested_language", nullable = false, updatable = false, length = 12)
    private String requestedLanguage;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 80)
    private String requestHash;

    @Column(name = "request_payload", nullable = false, columnDefinition = "text")
    private String requestPayload;

    @Column(name = "result_payload", columnDefinition = "text")
    private String resultPayload;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(length = 80)
    private String provider;

    @Column(length = 120)
    private String model;

    @Column(name = "model_version", length = 120)
    private String modelVersion;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (requestedAt == null) {
            requestedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static AiAnalysisJob queued(
        String tenantId,
        UUID companyId,
        AiAnalysisType analysisType,
        String entityType,
        UUID entityId,
        String requestHash,
        String requestedLanguage,
        String requestedBy,
        String requestPayload
    ) {
        AiAnalysisJob job = new AiAnalysisJob();
        job.setTenantId(tenantId);
        job.setCompanyId(companyId);
        job.setAnalysisType(analysisType);
        job.setEntityType(entityType);
        job.setEntityId(entityId);
        job.setRequestHash(requestHash);
        job.setRequestedLanguage(requestedLanguage == null || requestedLanguage.isBlank() ? "ru" : requestedLanguage);
        job.setRequestedBy(requestedBy == null || requestedBy.isBlank() ? "system" : requestedBy);
        job.setRequestPayload(requestPayload);
        job.setStatus(AiAnalysisJobStatus.QUEUED);
        job.setRequestedAt(LocalDateTime.now());
        return job;
    }
}

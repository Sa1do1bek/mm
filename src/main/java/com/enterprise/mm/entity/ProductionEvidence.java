package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "production_evidence")
public class ProductionEvidence extends BusinessEntity {
    @Column(name = "evidence_type", nullable = false, length = 80)
    private String evidenceType;

    @Column(name = "run_id", length = 160)
    private String runId;

    @Column(name = "environment_name", length = 120)
    private String environmentName;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "evidence_source", length = 120)
    private String evidenceSource;

    @Column(name = "overall_status", length = 40)
    private String overallStatus;

    @Column(name = "production_ready", nullable = false)
    private boolean productionReady;

    @Column(name = "blocks_production_count", nullable = false)
    private int blocksProductionCount;

    @Column(name = "warnings_count", nullable = false)
    private int warningsCount;

    @Column(name = "evidence_json", columnDefinition = "text")
    private String evidenceJson;

    @Column(name = "preflight_json", columnDefinition = "text")
    private String preflightJson;

    @Column(name = "checks_json", columnDefinition = "text")
    private String checksJson;

    @Column(name = "security_checklist_json", columnDefinition = "text")
    private String securityChecklistJson;

    @Column(name = "grafana_dashboard_uid", length = 160)
    private String grafanaDashboardUid;

    @Column(name = "grafana_dashboard_url", length = 700)
    private String grafanaDashboardUrl;

    @Column(name = "grafana_dashboard_snapshot_url", length = 700)
    private String grafanaDashboardSnapshotUrl;

    @Column(name = "evidence_attachment_id")
    private UUID evidenceAttachmentId;

    @Column(name = "dashboard_screenshot_attachment_id")
    private UUID dashboardScreenshotAttachmentId;

    @Column(name = "load_report_attachment_id")
    private UUID loadReportAttachmentId;

    @Column(name = "dr_report_attachment_id")
    private UUID drReportAttachmentId;

    @Column(name = "backup_report_attachment_id")
    private UUID backupReportAttachmentId;

    @Column(name = "security_report_attachment_id")
    private UUID securityReportAttachmentId;

    @Column(name = "incident_drill_at")
    private LocalDateTime incidentDrillAt;

    @Column(name = "incident_drill_summary", columnDefinition = "text")
    private String incidentDrillSummary;

    @Column(name = "backup_retention_days")
    private Integer backupRetentionDays;

    @Column(name = "backup_retention_verified", nullable = false)
    private boolean backupRetentionVerified;

    @Column(name = "latest_backup_checksum", length = 160)
    private String latestBackupChecksum;

    @Column(name = "restore_checksum", length = 160)
    private String restoreChecksum;

    @Column(name = "restore_started_at")
    private LocalDateTime restoreStartedAt;

    @Column(name = "restore_finished_at")
    private LocalDateTime restoreFinishedAt;

    @Column(name = "actual_rpo_minutes")
    private Integer actualRpoMinutes;

    @Column(name = "actual_rto_minutes")
    private Integer actualRtoMinutes;

    @Column(name = "pentest_status", length = 40)
    private String pentestStatus;

    @Column(name = "dependency_audit_status", length = 40)
    private String dependencyAuditStatus;

    @Column(name = "tls_cipher_scan_status", length = 40)
    private String tlsCipherScanStatus;

    @Column(name = "backup_access_review_status", length = 40)
    private String backupAccessReviewStatus;

    @Column(name = "security_notes", columnDefinition = "text")
    private String securityNotes;

    @Column(name = "monitoring_accepted_by", length = 160)
    private String monitoringAcceptedBy;

    @Column(name = "monitoring_accepted_at")
    private LocalDateTime monitoringAcceptedAt;

    @Column(name = "accepted_by", length = 160)
    private String acceptedBy;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "acceptance_notes", columnDefinition = "text")
    private String acceptanceNotes;
}
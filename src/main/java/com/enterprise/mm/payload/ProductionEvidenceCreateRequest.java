package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductionEvidenceCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    @Size(max = 80)
    private String evidenceType;
    @Size(max = 160)
    private String runId;
    @Size(max = 120)
    private String environmentName;
    @Size(max = 500)
    private String baseUrl;
    @Size(max = 120)
    private String evidenceSource;
    @Size(max = 40)
    private String overallStatus;
    private boolean productionReady;
    private int blocksProductionCount;
    private int warningsCount;
    private String evidenceJson;
    private String preflightJson;
    private String checksJson;
    private String securityChecklistJson;
    @Size(max = 160)
    private String grafanaDashboardUid;
    @Size(max = 700)
    private String grafanaDashboardUrl;
    @Size(max = 700)
    private String grafanaDashboardSnapshotUrl;
    private UUID evidenceAttachmentId;
    private UUID dashboardScreenshotAttachmentId;
    private UUID loadReportAttachmentId;
    private UUID drReportAttachmentId;
    private UUID backupReportAttachmentId;
    private UUID securityReportAttachmentId;
    private LocalDateTime incidentDrillAt;
    private String incidentDrillSummary;
    private Integer backupRetentionDays;
    private boolean backupRetentionVerified;
    @Size(max = 160)
    private String latestBackupChecksum;
    @Size(max = 160)
    private String restoreChecksum;
    private LocalDateTime restoreStartedAt;
    private LocalDateTime restoreFinishedAt;
    private Integer actualRpoMinutes;
    private Integer actualRtoMinutes;
    @Size(max = 40)
    private String pentestStatus;
    @Size(max = 40)
    private String dependencyAuditStatus;
    @Size(max = 40)
    private String tlsCipherScanStatus;
    @Size(max = 40)
    private String backupAccessReviewStatus;
    private String securityNotes;
    @Size(max = 160)
    private String monitoringAcceptedBy;
    private LocalDateTime monitoringAcceptedAt;
    @Size(max = 160)
    private String acceptedBy;
    private LocalDateTime acceptedAt;
    private String acceptanceNotes;
}

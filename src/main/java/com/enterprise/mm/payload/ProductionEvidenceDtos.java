package com.enterprise.mm.payload;

import java.util.UUID;

public final class ProductionEvidenceDtos {
    private ProductionEvidenceDtos() {
    }

    public record ProductionEvidenceAcceptanceRequest(
        String acceptedBy,
        String monitoringAcceptedBy,
        String notes
    ) {
    }

    public record ProductionEvidenceSecurityChecklistRequest(
        String pentestStatus,
        String dependencyAuditStatus,
        String tlsCipherScanStatus,
        String backupAccessReviewStatus,
        String notes,
        UUID securityReportAttachmentId
    ) {
    }

    public record ProductionOpsCheckRequest(
        String prometheusUrl,
        String alertmanagerUrl,
        String grafanaUrl,
        String grafanaDashboardUid,
        String grafanaDashboardUrl,
        String grafanaDashboardSnapshotUrl,
        boolean dashboardReviewed,
        boolean onCallEscalationVerified,
        boolean runbookDrillPassed
    ) {
    }
}
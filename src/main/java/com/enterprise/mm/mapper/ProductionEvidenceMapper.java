package com.enterprise.mm.mapper;

import com.enterprise.mm.payload.ProductionEvidenceResponse;
import com.enterprise.mm.payload.ProductionEvidenceUpdateRequest;
import com.enterprise.mm.payload.ProductionEvidenceCreateRequest;
import com.enterprise.mm.entity.ProductionEvidence;
import org.springframework.stereotype.Component;

@Component
public class ProductionEvidenceMapper {
    public ProductionEvidenceResponse toResponse(ProductionEvidence entity) {
        if (entity == null) {
            return null;
        }
        ProductionEvidenceResponse dto = new ProductionEvidenceResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setEvidenceType(entity.getEvidenceType());
        dto.setRunId(entity.getRunId());
        dto.setEnvironmentName(entity.getEnvironmentName());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setEvidenceSource(entity.getEvidenceSource());
        dto.setOverallStatus(entity.getOverallStatus());
        dto.setProductionReady(entity.isProductionReady());
        dto.setBlocksProductionCount(entity.getBlocksProductionCount());
        dto.setWarningsCount(entity.getWarningsCount());
        dto.setEvidenceJson(entity.getEvidenceJson());
        dto.setPreflightJson(entity.getPreflightJson());
        dto.setChecksJson(entity.getChecksJson());
        dto.setSecurityChecklistJson(entity.getSecurityChecklistJson());
        dto.setGrafanaDashboardUid(entity.getGrafanaDashboardUid());
        dto.setGrafanaDashboardUrl(entity.getGrafanaDashboardUrl());
        dto.setGrafanaDashboardSnapshotUrl(entity.getGrafanaDashboardSnapshotUrl());
        dto.setEvidenceAttachmentId(entity.getEvidenceAttachmentId());
        dto.setDashboardScreenshotAttachmentId(entity.getDashboardScreenshotAttachmentId());
        dto.setLoadReportAttachmentId(entity.getLoadReportAttachmentId());
        dto.setDrReportAttachmentId(entity.getDrReportAttachmentId());
        dto.setBackupReportAttachmentId(entity.getBackupReportAttachmentId());
        dto.setSecurityReportAttachmentId(entity.getSecurityReportAttachmentId());
        dto.setIncidentDrillAt(entity.getIncidentDrillAt());
        dto.setIncidentDrillSummary(entity.getIncidentDrillSummary());
        dto.setBackupRetentionDays(entity.getBackupRetentionDays());
        dto.setBackupRetentionVerified(entity.isBackupRetentionVerified());
        dto.setLatestBackupChecksum(entity.getLatestBackupChecksum());
        dto.setRestoreChecksum(entity.getRestoreChecksum());
        dto.setRestoreStartedAt(entity.getRestoreStartedAt());
        dto.setRestoreFinishedAt(entity.getRestoreFinishedAt());
        dto.setActualRpoMinutes(entity.getActualRpoMinutes());
        dto.setActualRtoMinutes(entity.getActualRtoMinutes());
        dto.setPentestStatus(entity.getPentestStatus());
        dto.setDependencyAuditStatus(entity.getDependencyAuditStatus());
        dto.setTlsCipherScanStatus(entity.getTlsCipherScanStatus());
        dto.setBackupAccessReviewStatus(entity.getBackupAccessReviewStatus());
        dto.setSecurityNotes(entity.getSecurityNotes());
        dto.setMonitoringAcceptedBy(entity.getMonitoringAcceptedBy());
        dto.setMonitoringAcceptedAt(entity.getMonitoringAcceptedAt());
        dto.setAcceptedBy(entity.getAcceptedBy());
        dto.setAcceptedAt(entity.getAcceptedAt());
        dto.setAcceptanceNotes(entity.getAcceptanceNotes());
        return dto;
    }

    public ProductionEvidence toEntity(ProductionEvidenceCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        ProductionEvidence entity = new ProductionEvidence();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEvidenceType(dto.getEvidenceType());
        entity.setRunId(dto.getRunId());
        entity.setEnvironmentName(dto.getEnvironmentName());
        entity.setBaseUrl(dto.getBaseUrl());
        entity.setEvidenceSource(dto.getEvidenceSource());
        entity.setOverallStatus(dto.getOverallStatus());
        entity.setProductionReady(dto.isProductionReady());
        entity.setBlocksProductionCount(dto.getBlocksProductionCount());
        entity.setWarningsCount(dto.getWarningsCount());
        entity.setEvidenceJson(dto.getEvidenceJson());
        entity.setPreflightJson(dto.getPreflightJson());
        entity.setChecksJson(dto.getChecksJson());
        entity.setSecurityChecklistJson(dto.getSecurityChecklistJson());
        entity.setGrafanaDashboardUid(dto.getGrafanaDashboardUid());
        entity.setGrafanaDashboardUrl(dto.getGrafanaDashboardUrl());
        entity.setGrafanaDashboardSnapshotUrl(dto.getGrafanaDashboardSnapshotUrl());
        entity.setEvidenceAttachmentId(dto.getEvidenceAttachmentId());
        entity.setDashboardScreenshotAttachmentId(dto.getDashboardScreenshotAttachmentId());
        entity.setLoadReportAttachmentId(dto.getLoadReportAttachmentId());
        entity.setDrReportAttachmentId(dto.getDrReportAttachmentId());
        entity.setBackupReportAttachmentId(dto.getBackupReportAttachmentId());
        entity.setSecurityReportAttachmentId(dto.getSecurityReportAttachmentId());
        entity.setIncidentDrillAt(dto.getIncidentDrillAt());
        entity.setIncidentDrillSummary(dto.getIncidentDrillSummary());
        entity.setBackupRetentionDays(dto.getBackupRetentionDays());
        entity.setBackupRetentionVerified(dto.isBackupRetentionVerified());
        entity.setLatestBackupChecksum(dto.getLatestBackupChecksum());
        entity.setRestoreChecksum(dto.getRestoreChecksum());
        entity.setRestoreStartedAt(dto.getRestoreStartedAt());
        entity.setRestoreFinishedAt(dto.getRestoreFinishedAt());
        entity.setActualRpoMinutes(dto.getActualRpoMinutes());
        entity.setActualRtoMinutes(dto.getActualRtoMinutes());
        entity.setPentestStatus(dto.getPentestStatus());
        entity.setDependencyAuditStatus(dto.getDependencyAuditStatus());
        entity.setTlsCipherScanStatus(dto.getTlsCipherScanStatus());
        entity.setBackupAccessReviewStatus(dto.getBackupAccessReviewStatus());
        entity.setSecurityNotes(dto.getSecurityNotes());
        entity.setMonitoringAcceptedBy(dto.getMonitoringAcceptedBy());
        entity.setMonitoringAcceptedAt(dto.getMonitoringAcceptedAt());
        entity.setAcceptedBy(dto.getAcceptedBy());
        entity.setAcceptedAt(dto.getAcceptedAt());
        entity.setAcceptanceNotes(dto.getAcceptanceNotes());
        return entity;
    }

    public void updateEntity(ProductionEvidence entity, ProductionEvidenceUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setEvidenceType(dto.getEvidenceType());
        entity.setRunId(dto.getRunId());
        entity.setEnvironmentName(dto.getEnvironmentName());
        entity.setBaseUrl(dto.getBaseUrl());
        entity.setEvidenceSource(dto.getEvidenceSource());
        entity.setOverallStatus(dto.getOverallStatus());
        entity.setProductionReady(dto.isProductionReady());
        entity.setBlocksProductionCount(dto.getBlocksProductionCount());
        entity.setWarningsCount(dto.getWarningsCount());
        entity.setEvidenceJson(dto.getEvidenceJson());
        entity.setPreflightJson(dto.getPreflightJson());
        entity.setChecksJson(dto.getChecksJson());
        entity.setSecurityChecklistJson(dto.getSecurityChecklistJson());
        entity.setGrafanaDashboardUid(dto.getGrafanaDashboardUid());
        entity.setGrafanaDashboardUrl(dto.getGrafanaDashboardUrl());
        entity.setGrafanaDashboardSnapshotUrl(dto.getGrafanaDashboardSnapshotUrl());
        entity.setEvidenceAttachmentId(dto.getEvidenceAttachmentId());
        entity.setDashboardScreenshotAttachmentId(dto.getDashboardScreenshotAttachmentId());
        entity.setLoadReportAttachmentId(dto.getLoadReportAttachmentId());
        entity.setDrReportAttachmentId(dto.getDrReportAttachmentId());
        entity.setBackupReportAttachmentId(dto.getBackupReportAttachmentId());
        entity.setSecurityReportAttachmentId(dto.getSecurityReportAttachmentId());
        entity.setIncidentDrillAt(dto.getIncidentDrillAt());
        entity.setIncidentDrillSummary(dto.getIncidentDrillSummary());
        entity.setBackupRetentionDays(dto.getBackupRetentionDays());
        entity.setBackupRetentionVerified(dto.isBackupRetentionVerified());
        entity.setLatestBackupChecksum(dto.getLatestBackupChecksum());
        entity.setRestoreChecksum(dto.getRestoreChecksum());
        entity.setRestoreStartedAt(dto.getRestoreStartedAt());
        entity.setRestoreFinishedAt(dto.getRestoreFinishedAt());
        entity.setActualRpoMinutes(dto.getActualRpoMinutes());
        entity.setActualRtoMinutes(dto.getActualRtoMinutes());
        entity.setPentestStatus(dto.getPentestStatus());
        entity.setDependencyAuditStatus(dto.getDependencyAuditStatus());
        entity.setTlsCipherScanStatus(dto.getTlsCipherScanStatus());
        entity.setBackupAccessReviewStatus(dto.getBackupAccessReviewStatus());
        entity.setSecurityNotes(dto.getSecurityNotes());
        entity.setMonitoringAcceptedBy(dto.getMonitoringAcceptedBy());
        entity.setMonitoringAcceptedAt(dto.getMonitoringAcceptedAt());
        entity.setAcceptedBy(dto.getAcceptedBy());
        entity.setAcceptedAt(dto.getAcceptedAt());
        entity.setAcceptanceNotes(dto.getAcceptanceNotes());
    }
}
package com.enterprise.mm.supply.workspace.payload;

import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AllowedAction;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AssignmentSummary;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.DeepLinkTarget;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.EntityRef;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MoneyValue;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.PartialError;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.QuantityValue;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SourceFreshness;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.UnavailableReason;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Source-backed transport contract for a single canonical Supply commitment.
 * Empty collections mean that no linked records were found; modeled legacy
 * gaps are carried separately in {@code limitations}.
 */
public final class SupplyCommitmentContextDtos {
    private SupplyCommitmentContextDtos() {
    }

    public record CommitmentHeader(
        String commitmentKey,
        String encodedCommitmentKey,
        String code,
        String status,
        String currentMilestone,
        LocalDate promisedDate,
        SupplySeverity severity,
        SupplyDataQuality dataQuality,
        EntityRef purchaseOrder,
        EntityRef supplier,
        EntityRef contract,
        EntityRef destinationWarehouse,
        AssignmentSummary assignment
    ) {
    }

    public record CommitmentLine(
        UUID id,
        UUID purchaseOrderLineId,
        Integer lineNumber,
        EntityRef material,
        QuantityValue planned,
        QuantityValue open,
        QuantityValue accepted,
        MoneyValue unitPrice,
        MoneyValue openAmount,
        String allocationStatus,
        SupplyDataQuality dataQuality,
        List<UnavailableReason> limitations
    ) {
        public CommitmentLine {
            limitations = immutable(limitations);
        }
    }

    public record ScheduleRevision(
        UUID id,
        Integer revisionNumber,
        LocalDate previousPromisedDate,
        LocalDate promisedDate,
        LocalDateTime acceptedAt,
        String acceptedBy,
        String reason,
        UUID evidenceId
    ) {
    }

    public record ScheduleContext(
        UUID id,
        String code,
        String status,
        LocalDate promisedDate,
        LocalDate eta,
        List<ScheduleRevision> revisions
    ) {
        public ScheduleContext {
            revisions = immutable(revisions);
        }
    }

    public record AsnContext(
        UUID id,
        String code,
        String status,
        LocalDateTime sentAt,
        LocalDateTime confirmedAt,
        LocalDate expectedArrivalDate,
        String carrierName,
        String trackingNumber,
        DeepLinkTarget target
    ) {
    }

    public record ImportContext(
        UUID id,
        String code,
        String status,
        String transportMode,
        LocalDate eta,
        LocalDate actualArrivalAt,
        String customsStatus,
        LocalDateTime customsClearanceAt,
        String delayReason,
        DeepLinkTarget target
    ) {
    }

    public record SupportingDocument(
        UUID id,
        String documentType,
        String code,
        String displayName,
        String status,
        LocalDate documentDate,
        MoneyValue amount,
        DeepLinkTarget target
    ) {
    }

    public record EvidenceItem(
        UUID id,
        String evidenceType,
        String displayName,
        String source,
        LocalDateTime uploadedAt,
        String actor,
        String comment,
        DeepLinkTarget target
    ) {
    }

    public record ReceivingContext(
        UUID id,
        String code,
        String status,
        EntityRef warehouse,
        LocalDate expectedDate,
        LocalDateTime receivedAt,
        QuantityValue accepted,
        QuantityValue rejected,
        QuantityValue damaged,
        DeepLinkTarget target
    ) {
    }

    public record AccreditationContext(
        boolean eligible,
        String status,
        String blockedReason,
        EntityRef application,
        String approvedScope,
        List<EntityRef> materialGroups,
        LocalDate validTo,
        String conditions,
        DeepLinkTarget target
    ) {
        public AccreditationContext {
            materialGroups = immutable(materialGroups);
        }
    }

    public record WorkspaceBlocker(
        String code,
        SupplySeverity severity,
        String messageKey,
        String message,
        String ownerRole,
        String resolutionAction,
        DeepLinkTarget target
    ) {
    }

    public record RelatedDocument(
        String documentType,
        UUID id,
        String code,
        String displayName,
        String status,
        DeepLinkTarget target
    ) {
    }

    public record IntegrationImpact(
        UUID id,
        String eventType,
        String status,
        Integer retryCount,
        String lastError,
        LocalDateTime createdAt,
        DeepLinkTarget target
    ) {
    }

    public record AuditTimelineItem(
        UUID id,
        String action,
        String actor,
        String details,
        LocalDateTime occurredAt
    ) {
    }

    public record SupplyCommitmentContextResponse(
        String commitmentKey,
        String encodedCommitmentKey,
        CommitmentHeader header,
        List<CommitmentLine> lines,
        ScheduleContext schedule,
        List<AsnContext> asns,
        List<ImportContext> imports,
        List<SupportingDocument> supportingDocuments,
        List<EvidenceItem> evidence,
        List<ReceivingContext> receiving,
        AccreditationContext accreditation,
        List<WorkspaceBlocker> blockers,
        List<RelatedDocument> relatedDocuments,
        List<IntegrationImpact> integrationImpact,
        List<AuditTimelineItem> auditTimeline,
        List<AllowedAction> allowedActions,
        List<UnavailableReason> limitations,
        List<SourceFreshness> sourceFreshness,
        List<PartialError> partialErrors,
        Instant generatedAt,
        String timeZone,
        String definitionVersion
    ) {
        public SupplyCommitmentContextResponse {
            lines = immutable(lines);
            asns = immutable(asns);
            imports = immutable(imports);
            supportingDocuments = immutable(supportingDocuments);
            evidence = immutable(evidence);
            receiving = immutable(receiving);
            blockers = immutable(blockers);
            relatedDocuments = immutable(relatedDocuments);
            integrationImpact = immutable(integrationImpact);
            auditTimeline = immutable(auditTimeline);
            allowedActions = immutable(allowedActions);
            limitations = immutable(limitations);
            sourceFreshness = immutable(sourceFreshness);
            partialErrors = immutable(partialErrors);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

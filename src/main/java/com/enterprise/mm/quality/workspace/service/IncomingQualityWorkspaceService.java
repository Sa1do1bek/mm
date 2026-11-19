package com.enterprise.mm.quality.workspace.service;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.LaboratoryTest;
import com.enterprise.mm.entity.QualityClaim;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.QualityNonconformance;
import com.enterprise.mm.entity.QualityQuarantineLot;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierEvaluation;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.AuditItem;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.ClaimItem;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.EntityRef;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.EvidenceItem;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionDetail;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionHeader;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionListItem;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionListResponse;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.IntegrationItem;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.LaboratoryItem;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.NonconformanceItem;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QuantityContext;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QuarantineItem;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueFilter;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueKey;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueSummary;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.StockImpact;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.SupplierImpact;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.WorkspaceAction;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.WorkspaceBlocker;
import com.enterprise.mm.quality.workspace.repository.IncomingQualityWorkspaceQueryRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.LaboratoryTestRepository;
import com.enterprise.mm.repository.QualityClaimRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.QualityNonconformanceRepository;
import com.enterprise.mm.repository.QualityQuarantineLotRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.SupplierEvaluationRepository;
import com.enterprise.mm.service.IntegrationPayloadRedactor;
import com.enterprise.mm.service.RoleDataScopeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncomingQualityWorkspaceService {
    private static final String INSPECTION_ENTITY_TYPE = "QualityInspection";
    private static final String QUARANTINE_ENTITY_TYPE = "QualityQuarantineLot";
    private static final String LABORATORY_ENTITY_TYPE = "LaboratoryTest";
    private static final String NONCONFORMANCE_ENTITY_TYPE = "QualityNonconformance";
    private static final String CLAIM_ENTITY_TYPE = "QualityClaim";
    private static final String LIMS_AGGREGATE_TYPE = "LIMS";
    private static final String SUPPLIER_PORTAL_AGGREGATE_TYPE = "SUPPLIER_PORTAL";
    private static final String ATTACHMENT_ENTITY_TYPE = "QUALITY_INSPECTION";
    private static final String LEGACY_ATTACHMENT_ENTITY_TYPE = "QUALITY_INSPECTIONS";

    private final IncomingQualityWorkspaceQueryRepository queryRepository;
    private final QualityInspectionRepository inspectionRepository;
    private final QualityQuarantineLotRepository quarantineRepository;
    private final LaboratoryTestRepository laboratoryRepository;
    private final QualityNonconformanceRepository nonconformanceRepository;
    private final QualityClaimRepository claimRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final SupplierEvaluationRepository supplierEvaluationRepository;
    private final AttachmentRepository attachmentRepository;
    private final AuditLogRepository auditLogRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final IntegrationPayloadRedactor integrationPayloadRedactor;
    private final RoleDataScopeService roleDataScopeService;
    private final IncomingQualityActionPolicy actionPolicy;

    @Transactional(readOnly = true)
    public List<QueueSummary> summary(QueueFilter filter) {
        QueueFilter scoped = scoped(filter);
        return java.util.Arrays.stream(QueueKey.values())
            .map(key -> new QueueSummary(
                key,
                queryRepository.count(new QueueFilter(
                    key,
                    scoped.search(),
                    scoped.warehouseId(),
                    scoped.supplierId()
                ))
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public InspectionListResponse list(QueueFilter filter, Pageable pageable) {
        QueueFilter scoped = scoped(filter);
        var page = queryRepository.search(scoped, pageable);
        List<InspectionListItem> items = page.getContent().stream()
            .map(this::listItem)
            .toList();
        return new InspectionListResponse(
            scoped.queue(),
            scoped,
            page.getTotalElements(),
            items,
            page.getNumber(),
            page.getSize(),
            page.getTotalPages(),
            LocalDateTime.now(),
            dataScope()
        );
    }

    @Transactional(readOnly = true)
    public InspectionDetail detail(UUID id) {
        QualityInspection inspection = requireInspection(id);
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<QualityQuarantineLot> quarantine = quarantineRepository
            .findByTenantIdAndCompanyIdAndQualityInspection_IdAndIsDeletedFalse(
                tenantId, companyId, inspection.getId());
        List<LaboratoryTest> laboratoryTests = laboratoryRepository
            .findByTenantIdAndCompanyIdAndQualityInspection_IdAndIsDeletedFalse(
                tenantId, companyId, inspection.getId());
        List<QualityNonconformance> nonconformances = nonconformanceRepository
            .findByTenantIdAndCompanyIdAndQualityInspection_IdAndIsDeletedFalse(
                tenantId, companyId, inspection.getId());
        List<QualityClaim> claims = claimRepository
            .findByTenantIdAndCompanyIdAndQualityInspectionIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId, companyId, inspection.getId());
        List<WorkspaceBlocker> blockers = blockers(inspection, laboratoryTests);

        return new InspectionDetail(
            header(inspection),
            quantities(inspection),
            stockImpact(inspection),
            sorted(quarantine).stream().map(this::quarantineItem).toList(),
            sorted(laboratoryTests).stream().map(this::laboratoryItem).toList(),
            sorted(nonconformances).stream().map(this::nonconformanceItem).toList(),
            claims.stream().map(this::claimItem).toList(),
            supplierImpact(inspection),
            evidence(inspection, laboratoryTests),
            auditTimeline(
                inspection, quarantine, laboratoryTests, nonconformances, claims),
            integrationImpact(inspection, laboratoryTests, claims),
            blockers,
            actionPolicy.actions(inspection),
            LocalDateTime.now(),
            dataScope()
        );
    }

    private QueueFilter scoped(QueueFilter filter) {
        QueueFilter normalized = (filter == null
            ? new QueueFilter(null, null, null, null)
            : filter).normalized();
        UUID effectiveWarehouseId = roleDataScopeService.effectiveWarehouseId(
            normalized.warehouseId());
        return new QueueFilter(
            normalized.queue(),
            normalized.search(),
            effectiveWarehouseId,
            normalized.supplierId()
        ).normalized();
    }

    private QualityInspection requireInspection(UUID id) {
        QualityInspection inspection = inspectionRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .filter(entity -> Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId()))
            .filter(entity -> "INCOMING".equalsIgnoreCase(entity.getInspectionType()) || "PRODUCTION".equalsIgnoreCase(entity.getInspectionType()))
            .filter(entity -> roleDataScopeService.canAccessWarehouse(entity.getWarehouseId()))
            .orElseThrow(() -> new NotFoundException(
                "Incoming quality inspection not found: " + id));
        return inspection;
    }

    private InspectionListItem listItem(QualityInspection inspection) {
        WorkspaceBlocker blocker = blockers(inspection, List.of()).stream()
            .findFirst().orElse(null);
        List<String> allowedActionCodes = actionPolicy.actions(inspection).stream()
            .filter(WorkspaceAction::enabled)
            .map(WorkspaceAction::code)
            .toList();
        return new InspectionListItem(
            inspection.getId(),
            inspection.getInspectionNumber(),
            displayName(inspection),
            inspection.getStatus(),
            inspection.getVersion(),
            ref(inspection.getSupplier()),
            ref(inspection.getPurchaseOrder()),
            ref(inspection.getMaterial()),
            ref(inspection.getWarehouse()),
            ref(inspection.getStorageLocation()),
            ref(inspection.getStockBatch()),
            nvl(inspection.getReceivedQuantity()),
            nvl(inspection.getQuarantinedQuantity()),
            inspection.getInspectorEmployeeId(),
            inspection.getDueDate(),
            overdue(inspection),
            blocker == null ? null : blocker.code(),
            blocker == null ? null : blocker.message(),
            nextAction(inspection),
            allowedActionCodes
        );
    }

    private InspectionHeader header(QualityInspection inspection) {
        return new InspectionHeader(
            inspection.getId(),
            inspection.getInspectionNumber(),
            displayName(inspection),
            inspection.getStatus(),
            inspection.getVersion(),
            inspection.getInspectionType(),
            ref(inspection.getSupplier()),
            ref(inspection.getPurchaseOrder()),
            ref(inspection.getPurchaseOrderItem()),
            ref(inspection.getStockMovement()),
            ref(inspection.getMaterial()),
            ref(inspection.getWarehouse()),
            ref(inspection.getStorageLocation()),
            ref(inspection.getStockBatch()),
            ref(inspection.getInspectorEmployee()),
            inspection.getInspectionDate(),
            inspection.getDueDate(),
            overdue(inspection),
            inspection.getDescription(),
            inspection.getConclusion(),
            inspection.getQuarantineStartedAt(),
            inspection.getQuarantineReleasedAt(),
            inspection.getInspectionStartedAt(),
            inspection.getCompletedAt(),
            nextAction(inspection)
        );
    }

    private QuantityContext quantities(QualityInspection inspection) {
        return new QuantityContext(
            nvl(inspection.getReceivedQuantity()),
            nvl(inspection.getSampleQuantity()),
            nvl(inspection.getAcceptedQuantity()),
            nvl(inspection.getRejectedQuantity()),
            nvl(inspection.getQuarantinedQuantity()),
            nvl(inspection.getQuarantinedQuantity())
        );
    }

    private StockImpact stockImpact(QualityInspection inspection) {
        StockBalance balance = stockBalanceRepository.findCompanyBalance(
            TenantContext.currentTenant(),
            inspection.getCompanyId(),
            inspection.getMaterialId(),
            inspection.getWarehouseId(),
            inspection.getStorageLocationId() == null,
            inspection.getStorageLocationId(),
            inspection.getStockBatchId() == null,
            inspection.getStockBatchId()
        ).orElse(null);
        if (balance == null) {
            return new StockImpact(
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
            );
        }
        return new StockImpact(
            balance.getId(),
            nvl(balance.getQuantity()),
            nvl(balance.getAvailableQuantity()),
            nvl(balance.getReservedQuantity()),
            nvl(balance.getQuarantineQuantity()),
            nvl(balance.getTotalValue()),
            balance.getBalanceAt()
        );
    }

    private QuarantineItem quarantineItem(QualityQuarantineLot lot) {
        return new QuarantineItem(
            lot.getId(),
            lot.getQuarantineNumber(),
            displayName(lot),
            lot.getStatus(),
            lot.getVersion(),
            nvl(lot.getQuarantinedQuantity()),
            nvl(lot.getReleasedQuantity()),
            nvl(lot.getRejectedQuantity()),
            nvl(lot.getRemainingQuantity()),
            lot.getDisposition(),
            lot.getQuarantineReason(),
            ref(lot.getQualityNonconformance()),
            ref(lot.getResponsibleEmployee()),
            lot.getQuarantineStartedAt(),
            lot.getReleasedAt(),
            lot.getRejectedAt(),
            lot.getClosedAt()
        );
    }

    private LaboratoryItem laboratoryItem(LaboratoryTest test) {
        return new LaboratoryItem(
            test.getId(),
            test.getSampleNumber(),
            displayName(test),
            test.getStatus(),
            test.getVersion(),
            test.getTestType(),
            test.getTestMethod(),
            test.getSpecification(),
            test.getMeasuredValue(),
            test.getResult(),
            test.isPassed(),
            test.getRequestedAt(),
            test.getStartedAt(),
            test.getCompletedAt(),
            test.getLabTechnicianEmployeeId(),
            test.getProtocolAttachmentId(),
            test.getLimsSampleId(),
            test.getLimsStatus(),
            test.getLimsRequestedAt(),
            test.getLimsResultReceivedAt()
        );
    }

    private NonconformanceItem nonconformanceItem(QualityNonconformance item) {
        return new NonconformanceItem(
            item.getId(),
            item.getCode(),
            displayName(item),
            item.getStatus(),
            item.getVersion(),
            item.getSeverity(),
            item.getNonconformanceType(),
            nvl(item.getDetectedQuantity()),
            item.getDefectDescription(),
            item.getRootCause(),
            item.getDisposition(),
            item.isCapaRequired(),
            ref(item.getCorrectiveAction()),
            ref(item.getClaim()),
            item.getDetectedAt(),
            item.getClosedAt()
        );
    }

    private ClaimItem claimItem(QualityClaim claim) {
        return new ClaimItem(
            claim.getId(),
            claim.getClaimNumber(),
            displayName(claim),
            claim.getStatus(),
            claim.getVersion(),
            claim.getClaimDate(),
            nvl(claim.getClaimAmount()),
            claim.getCurrencyCode(),
            claim.getRequestedAction(),
            claim.getSupplierResponse(),
            claim.getResponseDueDate(),
            ref(claim.getCorrectiveAction()),
            claim.getSentAt(),
            claim.getAcknowledgedAt(),
            claim.getClosedAt()
        );
    }

    private SupplierImpact supplierImpact(QualityInspection inspection) {
        Supplier supplier = inspection.getSupplier();
        SupplierEvaluation evaluation = inspection.getStockMovementId() == null
            ? null
            : supplierEvaluationRepository
                .findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    inspection.getCompanyId(),
                    inspection.getStockMovementId()
                ).orElse(null);
        return new SupplierImpact(
            ref(supplier),
            supplier == null ? BigDecimal.ZERO : nvl(supplier.getRating()),
            supplier == null ? null : supplier.getCategory(),
            evaluation == null ? null : evaluation.getId(),
            evaluation == null ? null : evaluation.getStatus(),
            evaluation == null ? null : evaluation.getEvaluationSource(),
            evaluation == null ? BigDecimal.ZERO : nvl(evaluation.getQualityScore()),
            evaluation == null ? BigDecimal.ZERO : nvl(evaluation.getTotalScore()),
            evaluation == null ? BigDecimal.ZERO : nvl(evaluation.getAcceptedQuantity()),
            evaluation == null ? BigDecimal.ZERO : nvl(evaluation.getRejectedQuantity()),
            evaluation == null ? null : evaluation.getEvaluatedAt()
        );
    }

    private List<EvidenceItem> evidence(
        QualityInspection inspection,
        List<LaboratoryTest> laboratoryTests
    ) {
        Map<UUID, Attachment> attachments = new LinkedHashMap<>();
        String tenantId = TenantContext.currentTenant();
        addEvidence(attachments, attachmentRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId, inspection.getCompanyId(),
                ATTACHMENT_ENTITY_TYPE, inspection.getId()));
        addEvidence(attachments, attachmentRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId, inspection.getCompanyId(),
                LEGACY_ATTACHMENT_ENTITY_TYPE, inspection.getId()));
        addEvidenceById(
            attachments, tenantId, inspection.getCompanyId(),
            inspection.getQualityPassportAttachmentId());
        laboratoryTests.forEach(test -> addEvidenceById(
            attachments, tenantId, inspection.getCompanyId(), test.getProtocolAttachmentId()));
        return attachments.values().stream()
            .sorted(Comparator.comparing(
                Attachment::getUploadedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(attachment -> new EvidenceItem(
                attachment.getId(),
                attachment.getAttachmentType(),
                attachment.getOriginalFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getUploadedAt(),
                attachment.isDownloadable(),
                attachment.isPreviewable()
            ))
            .toList();
    }

    private void addEvidence(Map<UUID, Attachment> target, List<Attachment> values) {
        values.forEach(attachment -> target.putIfAbsent(attachment.getId(), attachment));
    }

    private void addEvidenceById(
        Map<UUID, Attachment> target,
        String tenantId,
        UUID companyId,
        UUID attachmentId
    ) {
        if (attachmentId == null) {
            return;
        }
        attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            tenantId, companyId, attachmentId)
            .ifPresent(attachment -> target.putIfAbsent(attachment.getId(), attachment));
    }

    private List<AuditItem> auditTimeline(
        QualityInspection inspection,
        List<QualityQuarantineLot> quarantine,
        List<LaboratoryTest> laboratoryTests,
        List<QualityNonconformance> nonconformances,
        List<QualityClaim> claims
    ) {
        Map<UUID, AuditLog> logs = new LinkedHashMap<>();
        String tenantId = TenantContext.currentTenant();
        UUID companyId = inspection.getCompanyId();
        addAudit(logs, tenantId, companyId, INSPECTION_ENTITY_TYPE, inspection.getId());
        quarantine.forEach(item -> addAudit(
            logs, tenantId, companyId, QUARANTINE_ENTITY_TYPE, item.getId()));
        laboratoryTests.forEach(item -> addAudit(
            logs, tenantId, companyId, LABORATORY_ENTITY_TYPE, item.getId()));
        nonconformances.forEach(item -> addAudit(
            logs, tenantId, companyId, NONCONFORMANCE_ENTITY_TYPE, item.getId()));
        claims.forEach(item -> addAudit(
            logs, tenantId, companyId, CLAIM_ENTITY_TYPE, item.getId()));
        return logs.values().stream()
            .sorted(Comparator.comparing(
                AuditLog::getOccurredAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                    AuditLog::getId,
                    Comparator.nullsLast(Comparator.naturalOrder())))
            .map(log -> new AuditItem(
                log.getId(),
                log.getEntityType(),
                log.getEntityId(),
                log.getAction(),
                log.getFullName(),
                integrationPayloadRedactor.sanitizeText(log.getDetails()),
                log.getOccurredAt()
            ))
            .toList();
    }

    private void addAudit(
        Map<UUID, AuditLog> target,
        String tenantId,
        UUID companyId,
        String entityType,
        UUID entityId
    ) {
        if (entityId == null) {
            return;
        }
        auditLogRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
                tenantId, companyId, entityType, entityId)
            .forEach(log -> target.putIfAbsent(log.getId(), log));
    }

    private List<IntegrationItem> integrationImpact(
        QualityInspection inspection,
        List<LaboratoryTest> laboratoryTests,
        List<QualityClaim> claims
    ) {
        Map<UUID, IntegrationEvent> events = new LinkedHashMap<>();
        String tenantId = TenantContext.currentTenant();
        UUID companyId = inspection.getCompanyId();
        addIntegration(
            events, tenantId, companyId, INSPECTION_ENTITY_TYPE, inspection.getId());
        laboratoryTests.forEach(test -> {
            addIntegration(
                events, tenantId, companyId, LIMS_AGGREGATE_TYPE, test.getId());
            addIntegration(
                events, tenantId, companyId, LABORATORY_ENTITY_TYPE, test.getId());
        });
        claims.forEach(claim -> addIntegration(
            events, tenantId, companyId, SUPPLIER_PORTAL_AGGREGATE_TYPE, claim.getId()));
        return events.values().stream()
            .sorted(Comparator.comparing(
                IntegrationEvent::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                    IntegrationEvent::getId,
                    Comparator.nullsLast(Comparator.naturalOrder())))
            .map(event -> integrationItem(event))
            .toList();
    }

    private void addIntegration(
        Map<UUID, IntegrationEvent> target,
        String tenantId,
        UUID companyId,
        String aggregateType,
        UUID aggregateId
    ) {
        if (aggregateId == null) {
            return;
        }
        integrationEventRepository
            .findByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId, companyId, aggregateType, aggregateId)
            .forEach(event -> target.putIfAbsent(event.getId(), event));
    }

    private IntegrationItem integrationItem(IntegrationEvent event) {
        return new IntegrationItem(
            event.getId(),
            event.getEventType(),
            event.getAggregateType(),
            event.getAggregateId(),
            event.getStatus(),
            event.getRetryCount(),
            event.getNextRetryAt(),
            event.getLastAttemptAt(),
            integrationPayloadRedactor.sanitizeText(event.getExternalId()),
            integrationPayloadRedactor.sanitizeText(event.getLastError()),
            event.getCreatedAt()
        );
    }

    private List<WorkspaceBlocker> blockers(
        QualityInspection inspection,
        List<LaboratoryTest> laboratoryTests
    ) {
        List<WorkspaceBlocker> blockers = new ArrayList<>();
        QualityInspectionStatus status = status(inspection);
        if (overdue(inspection)) {
            blockers.add(new WorkspaceBlocker(
                "INSPECTION_OVERDUE",
                "Inspection due date has passed",
                "HIGH",
                "MM_MANAGER",
                nextAction(inspection)
            ));
        }
        if (status == QualityInspectionStatus.LAB_TESTING) {
            long incomplete = laboratoryTests.stream()
                .filter(test -> !List.of("PASSED", "FAILED").contains(test.getStatus()))
                .count();
            blockers.add(new WorkspaceBlocker(
                "LAB_RESULT_PENDING",
                incomplete > 0
                    ? incomplete + " laboratory test(s) remain incomplete"
                    : "Laboratory result is pending",
                "MEDIUM",
                "WAREHOUSE_OPERATOR",
                "AWAIT_LAB_RESULT"
            ));
        }
        if (status == QualityInspectionStatus.PASSED || status == QualityInspectionStatus.FAILED) {
            blockers.add(new WorkspaceBlocker(
                "QUALITY_DECISION_REQUIRED",
                "Release or rejection decision is required",
                status == QualityInspectionStatus.FAILED ? "HIGH" : "MEDIUM",
                "MM_MANAGER",
                "RELEASE_OR_REJECT"
            ));
            if (nvl(inspection.getQuarantinedQuantity()).signum() <= 0) {
                blockers.add(new WorkspaceBlocker(
                    "NO_QUARANTINED_QUANTITY",
                    "No quarantined quantity remains for a stock decision",
                    "HIGH",
                    "WAREHOUSE_MANAGER",
                    "RECONCILE_STOCK"
                ));
            }
        }
        return List.copyOf(blockers);
    }

    private String nextAction(QualityInspection inspection) {
        return switch (status(inspection)) {
            case DRAFT -> "QUARANTINE";
            case IN_QUARANTINE -> "SAMPLING";
            case SAMPLING -> "SEND_TO_LAB";
            case LAB_TESTING -> "AWAIT_LAB_RESULT";
            case PASSED, FAILED -> "RELEASE_OR_REJECT";
            case RELEASED, REJECTED, CANCELLED -> "REVIEW_OUTCOME";
        };
    }

    private boolean overdue(QualityInspection inspection) {
        QualityInspectionStatus status = status(inspection);
        return inspection.getDueDate() != null
            && inspection.getDueDate().isBefore(LocalDate.now())
            && !List.of(
                QualityInspectionStatus.RELEASED,
                QualityInspectionStatus.REJECTED,
                QualityInspectionStatus.CANCELLED
            ).contains(status);
    }

    private QualityInspectionStatus status(QualityInspection inspection) {
        return QualityInspectionStatus.valueOf(
            inspection.getStatus().trim().toUpperCase(Locale.ROOT));
    }

    private EntityRef ref(BusinessEntity entity) {
        if (entity == null) {
            return null;
        }
        return new EntityRef(
            entity.getId(),
            entity.getCode(),
            displayName(entity),
            entity.getStatus()
        );
    }

    private String displayName(BusinessEntity entity) {
        if (entity instanceof Supplier supplier
            && supplier.getLegalName() != null
            && !supplier.getLegalName().isBlank()) {
            return supplier.getLegalName();
        }
        if (entity instanceof StockBatch batch
            && batch.getBatchNumber() != null
            && !batch.getBatchNumber().isBlank()) {
            return batch.getBatchNumber();
        }
        if (entity.getName() != null && !entity.getName().isBlank()) {
            return entity.getName();
        }
        if (entity.getCode() != null && !entity.getCode().isBlank()) {
            return entity.getCode();
        }
        return "—";
    }

    private <T extends BusinessEntity> List<T> sorted(List<T> values) {
        return values.stream()
            .sorted(Comparator.comparing(
                BusinessEntity::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String dataScope() {
        var scope = roleDataScopeService.currentScope();
        if (scope.warehouseScoped()) {
            return scope.warehouseId() == null
                ? "WAREHOUSE:NONE"
                : "WAREHOUSE:" + scope.warehouseId();
        }
        return "COMPANY:" + CompanyContext.currentCompany();
    }
}

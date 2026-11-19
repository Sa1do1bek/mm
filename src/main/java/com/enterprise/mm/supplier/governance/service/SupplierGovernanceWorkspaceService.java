package com.enterprise.mm.supplier.governance.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.entity.SupplierActivationRecord;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.entity.SupplierKycCheck;
import com.enterprise.mm.entity.SupplierLicense;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.SupplierUnblockRequest;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.SupplierEligibilityDtos.MaterialGroupEligibility;
import com.enterprise.mm.payload.SupplierEligibilityDtos.EffectiveSupplierEligibility;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.payload.SupplierQualificationResponse;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierKycCheckRepository;
import com.enterprise.mm.repository.SupplierLicenseRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierUnblockRequestRepository;
import com.enterprise.mm.repository.SupplierActivationRecordRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.SupplierComplianceService;
import com.enterprise.mm.service.SupplierQualificationService;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.supplier.governance.enums.SupplierGovernanceQueueKey;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.AccreditationApplicationContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.AccreditationDecisionContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.ActivityEventContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.AffectedDocumentContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.AuditContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.CapaContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.EntityRef;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.EvidenceContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.GovernanceAction;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.GovernanceOverview;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.IntegrationContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.KycContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.LicenseContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.QualificationContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.QueueDefinition;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.QueueResponse;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.RestrictionContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.ScopeMatrixRow;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceCaseRow;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceContext;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierGovernanceFilter;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierHeader;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.SupplierPerformanceSummary;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.UnblockRequestContext;
import com.enterprise.mm.supplier.governance.repository.SupplierGovernanceQueryRepository;
import com.enterprise.mm.supplier.governance.repository.SupplierPerformanceQueryRepository;
import com.enterprise.mm.supplier.governance.repository.SupplierPerformanceQueryRepository.PerformanceQuery;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistItem;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistService;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.OverallStatus;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierGovernanceWorkspaceService {
    private static final List<String> OPEN_PO_STATUSES = List.of("APPROVED", "SENT", "PARTIALLY_RECEIVED");
    private static final List<String> MANAGER_ROLES = List.of(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "SUPPLIER_MANAGER"
    );
    private static final List<String> COMPLIANCE_ACTION_ROLES = List.of(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "SUPPLIER_MANAGER", "COMPLIANCE_MANAGER"
    );
    private static final String ACCREDITATION_APPROVAL_MODULE_KEY = "SUPPLIER_ACCREDITATION";

    private final SupplierGovernanceQueryRepository queryRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierAccreditationApplicationRepository applicationRepository;
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final ApprovalEngineService approvalEngineService;
    private final SupplierAccreditationDecisionRepository decisionRepository;
    private final SupplierAccreditationDocumentRepository documentRepository;
    private final SupplierLicenseRepository licenseRepository;
    private final SupplierKycCheckRepository kycRepository;
    private final SupplierCorrectiveActionRepository correctiveActionRepository;
    private final SupplierUnblockRequestRepository unblockRequestRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final AdvanceShippingNoticeRepository asnRepository;
    private final AuditLogRepository auditLogRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final MaterialGroupRepository materialGroupRepository;
    private final SupplierQualificationService qualificationService;
    private final SupplierAccreditationService accreditationService;
    private final SupplierComplianceService complianceService;
    private final SupplierActivationRecordRepository activationRecordRepository;
    private final SupplierReadinessService readinessService;
    private final SupplierEvidenceChecklistService checklistService;
    private final SupplierPerformanceQueryRepository performanceRepository;

    @Transactional(readOnly = true)
    public QueueDefinition queueDefinition(SupplierGovernanceFilter filter) {
        return queueDefinition(filter, false);
    }

    private QueueDefinition queueDefinition(SupplierGovernanceFilter filter, boolean forceCompanyScope) {
        SupplierGovernanceFilter normalized = filter.normalized();
        long count = derivedReadinessQueue(normalized.queue())
            ? readinessCandidates(normalized, forceCompanyScope).size()
            : normalized.queue() == SupplierGovernanceQueueKey.RECEIVING_BLOCKED
            ? receivingBlockedSuppliers(normalized, forceCompanyScope).size()
            : forceCompanyScope
                ? queryRepository.countForCompany(normalized)
                : queryRepository.count(normalized);
        return new QueueDefinition(
            normalized.queue(),
            "supplierGovernance.queue." + normalized.queue().name(),
            count,
            normalized);
    }

    @Transactional(readOnly = true)
    public List<QueueDefinition> queues(Integer horizonDays, UUID purchaseOrderId) {
        return queues(horizonDays, purchaseOrderId, false);
    }

    @Transactional(readOnly = true)
    public List<QueueDefinition> queuesForCompany(Integer horizonDays, UUID purchaseOrderId) {
        return queues(horizonDays, purchaseOrderId, true);
    }

    private List<QueueDefinition> queues(
        Integer horizonDays,
        UUID purchaseOrderId,
        boolean forceCompanyScope
    ) {
        return Arrays.stream(SupplierGovernanceQueueKey.values())
            .filter(this::operationalQueue)
            .map(key -> queueDefinition(
                new SupplierGovernanceFilter(key, null, horizonDays, purchaseOrderId),
                forceCompanyScope))
            .toList();
    }

    @Transactional(readOnly = true)
    public GovernanceOverview overview(Integer horizonDays) {
        return overview(horizonDays, false);
    }

    @Transactional(readOnly = true)
    public GovernanceOverview overviewForCompany(Integer horizonDays) {
        return overview(horizonDays, true);
    }

    @Transactional(readOnly = true)
    public long receivingBlockedCountForCompany() {
        return receivingBlockedSuppliers(
            new SupplierGovernanceFilter(SupplierGovernanceQueueKey.RECEIVING_BLOCKED, null, null, null),
            true).size();
    }

    private GovernanceOverview overview(Integer horizonDays, boolean forceCompanyScope) {
        Map<SupplierGovernanceQueueKey, Long> counts = queues(
            horizonDays, null, forceCompanyScope).stream()
            .collect(Collectors.toMap(QueueDefinition::key, QueueDefinition::count));
        return new GovernanceOverview(
            counts.getOrDefault(SupplierGovernanceQueueKey.ACCREDITATION_REVIEW, 0L),
            counts.getOrDefault(SupplierGovernanceQueueKey.EXPIRING_EVIDENCE, 0L),
            counts.getOrDefault(SupplierGovernanceQueueKey.KYC_COMPLIANCE, 0L)
                + counts.getOrDefault(SupplierGovernanceQueueKey.LICENSE_RISK, 0L),
            counts.getOrDefault(SupplierGovernanceQueueKey.OPEN_CAPA, 0L),
            counts.getOrDefault(SupplierGovernanceQueueKey.RECEIVING_BLOCKED, 0L),
            LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public QueueResponse queue(SupplierGovernanceFilter filter, Pageable pageable) {
        return queue(filter, pageable, false);
    }

    @Transactional(readOnly = true)
    public QueueResponse queueForCompany(SupplierGovernanceFilter filter, Pageable pageable) {
        return queue(filter, pageable, true);
    }

    private QueueResponse queue(
        SupplierGovernanceFilter filter,
        Pageable pageable,
        boolean forceCompanyScope
    ) {
        SupplierGovernanceFilter normalized = filter.normalized();
        Page<Supplier> suppliers = derivedReadinessQueue(normalized.queue())
            ? readinessPage(normalized, pageable, forceCompanyScope)
            : normalized.queue() == SupplierGovernanceQueueKey.RECEIVING_BLOCKED
            ? receivingBlockedPage(normalized, pageable, forceCompanyScope)
            : forceCompanyScope
                ? queryRepository.searchForCompany(normalized, pageable)
                : queryRepository.search(normalized, pageable);
        return new QueueResponse(
            normalized.queue(),
            normalized,
            suppliers.getTotalElements(),
            suppliers.getContent().stream().map(supplier -> row(supplier, normalized)).toList(),
            suppliers.getNumber(),
            suppliers.getSize(),
            suppliers.getTotalPages(),
            LocalDateTime.now(),
            forceCompanyScope ? "COMPANY" : dataScope());
    }

    @Transactional(readOnly = true)
    public SupplierGovernanceContext context(UUID supplierId, UUID originPurchaseOrderId) {
        return context(supplierId, originPurchaseOrderId, null, null, List.of(), null, null);
    }

    @Transactional(readOnly = true)
    public SupplierGovernanceContext context(
        UUID supplierId,
        UUID originPurchaseOrderId,
        LocalDate performanceFrom,
        LocalDate performanceTo,
        List<RequestType> requestTypes,
        UUID materialGroupId
    ) {
        return context(supplierId, originPurchaseOrderId, performanceFrom, performanceTo, requestTypes, materialGroupId, null);
    }

    @Transactional(readOnly = true)
    public SupplierGovernanceContext context(
        UUID supplierId,
        UUID originPurchaseOrderId,
        LocalDate performanceFrom,
        LocalDate performanceTo,
        List<RequestType> requestTypes,
        UUID materialGroupId,
        LocalDate asOfDate
    ) {
        LocalDate today = asOfDate == null ? LocalDate.now() : asOfDate;
        LocalDate from = performanceFrom == null ? today.minusMonths(12) : performanceFrom;
        LocalDate to = performanceTo == null ? today : performanceTo;
        validatePerformancePeriod(from, to);
        Supplier supplier = requireSupplier(supplierId);
        if (originPurchaseOrderId != null) {
            requireOriginPurchaseOrder(supplier, originPurchaseOrderId);
        }
        List<SupplierAccreditationApplication> applications = applications(supplierId);
        List<SupplierAccreditationDecision> decisions = decisions(applications);
        List<SupplierAccreditationDocument> documents = documents(applications);
        List<SupplierLicense> licenses = licenseRepository
            .findByTenantIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), supplierId)
            .stream().filter(this::currentCompany).toList();
        List<SupplierKycCheck> kycChecks = kycRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), supplier.getCompanyId(), supplierId);
        List<SupplierCorrectiveAction> correctiveActions = correctiveActionRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), supplier.getCompanyId(), supplierId);
        List<SupplierUnblockRequest> unblockRequests = unblockRequestRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), supplier.getCompanyId(), supplierId);
        List<SupplierActivationRecord> activationRecords = activationRecordRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByActivatedAtDesc(
                TenantContext.currentTenant(), supplier.getCompanyId(), supplierId);
        List<PurchaseOrder> purchaseOrders = purchaseOrders(supplierId);
        SupplierQualificationResponse qualification = qualificationService.get(supplierId);
        SupplierAccreditationApplication latest = applications.isEmpty() ? null : applications.get(0);
        SupplierComplianceSummary complianceSummary = complianceService.summarize(supplierId);
        SupplierReadinessSummary readiness = readinessService.get(supplierId, today);
        List<SupplierEvidenceChecklistItem> evidenceChecklist = applications.stream()
            .flatMap(application -> checklistService.checklist(application, today).stream())
            .toList();
        SupplierPerformanceSummary performance = performanceRepository.summary(new PerformanceQuery(
            TenantContext.currentTenant(),
            supplier.getCompanyId(),
            supplierId,
            from,
            to,
            performanceFrom == null && performanceTo == null ? "LAST_12_MONTHS" : null,
            requestTypes,
            materialGroupId
        ));
        List<AuditContext> audits = auditTimeline(supplier, applications, documents, decisions, activationRecords,
            correctiveActions, unblockRequests);
        ScopeEligibilitySnapshot scopeEligibility = scopeEligibility(
            supplierId, applications, decisions, originPurchaseOrderId, today);

        return new SupplierGovernanceContext(
            header(supplier),
            qualification(qualification),
            applications.stream().map(this::applicationContext).toList(),
            decisions.stream().map(this::decisionContext).toList(),
            scopeEligibility.rows(),
            scopeEligibility.overallStatus(),
            documents.stream().map(this::evidenceContext).toList(),
            licenses.stream().map(this::licenseContext).toList(),
            kycChecks.stream().map(this::kycContext).toList(),
            correctiveActions.stream().map(this::capaContext).toList(),
            unblockRequests.stream().map(this::unblockContext).toList(),
            affectedDocuments(supplier, purchaseOrders),
            allowedActions(latest, qualification, readiness),
            audits,
            activityTimeline(audits),
            integrationImpact(supplier, purchaseOrders),
            complianceSummary,
            performance,
            readiness,
            evidenceChecklist,
            originPurchaseOrderId,
            LocalDateTime.now(),
            "COMPANY");
    }

    private void validatePerformancePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new com.enterprise.mm.error.BadRequestException("performanceFrom must be before or equal to performanceTo");
        }
        if (java.time.temporal.ChronoUnit.MONTHS.between(from, to) > 36) {
            throw new com.enterprise.mm.error.BadRequestException("Performance period cannot exceed 36 months");
        }
    }

    private boolean operationalQueue(SupplierGovernanceQueueKey queue) {
        return switch (queue) {
            case ALL, NEEDS_ACTION, IN_ACCREDITATION, READY_TO_ACTIVATE, ACTIVE, BLOCKED, EXPIRING -> true;
            default -> false;
        };
    }

    private boolean derivedReadinessQueue(SupplierGovernanceQueueKey queue) {
        return queue == SupplierGovernanceQueueKey.NEEDS_ACTION
            || queue == SupplierGovernanceQueueKey.READY_TO_ACTIVATE;
    }

    private Page<Supplier> readinessPage(
        SupplierGovernanceFilter filter,
        Pageable pageable,
        boolean forceCompanyScope
    ) {
        List<Supplier> candidates = readinessCandidates(filter, forceCompanyScope);
        int from = Math.min((int) pageable.getOffset(), candidates.size());
        int to = Math.min(from + pageable.getPageSize(), candidates.size());
        return new PageImpl<>(candidates.subList(from, to), pageable, candidates.size());
    }

    private List<Supplier> readinessCandidates(
        SupplierGovernanceFilter filter,
        boolean forceCompanyScope
    ) {
        SupplierGovernanceFilter all = new SupplierGovernanceFilter(
            SupplierGovernanceQueueKey.ALL, filter.search(), filter.horizonDays(), filter.purchaseOrderId());
        List<Supplier> candidates = forceCompanyScope
            ? queryRepository.findAllForCompany(all)
            : queryRepository.findAll(all);
        return candidates.stream()
            .filter(supplier -> {
                SupplierReadinessSummary readiness = readinessService.get(supplier.getId());
                if (filter.queue() == SupplierGovernanceQueueKey.READY_TO_ACTIVATE) {
                    return readiness.overallStatus() == OverallStatus.READY_TO_ACTIVATE;
                }
                return readiness.nextAction() != null
                    && readiness.nextAction().enabled()
                    && readiness.overallStatus() != OverallStatus.ACTIVE;
            })
            .sorted(java.util.Comparator.comparing(
                Supplier::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .toList();
    }

    private String currentStage(SupplierReadinessSummary readiness) {
        return readiness.stages().stream()
            .filter(stage -> stage.state() != StageState.COMPLETED)
            .map(stage -> stage.key().name())
            .findFirst()
            .orElse("ACTIVATION");
    }

    private Page<Supplier> receivingBlockedPage(
        SupplierGovernanceFilter filter,
        Pageable pageable,
        boolean forceCompanyScope
    ) {
        List<Supplier> all = receivingBlockedSuppliers(filter, forceCompanyScope);
        int from = Math.min((int) pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), pageable, all.size());
    }

    private List<Supplier> receivingBlockedSuppliers(SupplierGovernanceFilter filter) {
        return receivingBlockedSuppliers(filter, false);
    }

    private List<Supplier> receivingBlockedSuppliers(
        SupplierGovernanceFilter filter,
        boolean forceCompanyScope
    ) {
        List<Supplier> suppliers = forceCompanyScope
            ? queryRepository.findAllForCompany(filter)
            : queryRepository.findAll(filter);
        return suppliers.stream()
            .filter(supplier -> firstReceivingBlocker(supplier.getId()) != null)
            .toList();
    }

    private SupplierGovernanceCaseRow row(Supplier supplier, SupplierGovernanceFilter filter) {
        List<SupplierAccreditationApplication> applications = applications(supplier.getId());
        SupplierAccreditationApplication latest = applications.isEmpty() ? null : applications.get(0);
        SupplierQualificationResponse qualification = qualificationService.get(supplier.getId());
        SupplierReadinessSummary readiness = readinessService.get(supplier.getId());
        List<PurchaseOrder> purchaseOrders = purchaseOrders(supplier.getId());
        String receivingBlocker = filter.queue() == SupplierGovernanceQueueKey.RECEIVING_BLOCKED
            ? firstReceivingBlocker(supplier.getId())
            : null;
        String sourceType = sourceType(filter.queue());
        UUID sourceId = latest == null ? supplier.getId() : latest.getId();
        String sourceCode = latest == null ? supplier.getCode() : latest.getCode();
        String sourceStatus = latest == null ? supplier.getStatus() : latest.getStatus();
        LocalDate dueDate = dueDate(filter.queue(), latest, supplier.getId());
        String blockerCode = receivingBlocker == null ? qualificationBlocker(qualification) : "SUPPLIER_RECEIVING_BLOCKED";
        String blockerMessage = receivingBlocker == null ? qualification.blockedReason() : receivingBlocker;
        // Same effectiveEligibility projection as the dossier's scope matrix (UX-LIVE-02), so the
        // list row never shows a status (e.g. "expired") that disagrees with the dossier.
        String eligibilityStatus = accreditationService
            .effectiveEligibility(supplier.getId(), List.of(), LocalDate.now(), "supplier governance list")
            .overallStatus();
        return new SupplierGovernanceCaseRow(
            ref(supplier),
            qualification.status().name(),
            latest == null ? null : latest.getRiskScore(),
            sourceType,
            sourceId,
            sourceCode,
            sourceStatus,
            readiness.overallStatus().name(),
            readiness.readinessPercent(),
            currentStage(readiness),
            readiness.nextAction() == null ? nextAction(filter.queue(), latest) : readiness.nextAction().code(),
            blockerCode,
            blockerMessage,
            readiness.nextAction() == null ? "SUPPLIER_MANAGER" : readiness.nextAction().ownerRole(),
            readiness.blockers().stream().map(blocker -> blocker.dueDate())
                .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(dueDate),
            readiness.scopes().stream().filter(scope -> scope.effective()).count(),
            readiness.scopes().stream().filter(scope -> !scope.effective()).count(),
            purchaseOrders.stream().filter(this::openPurchaseOrder).count(),
            latest == null ? supplier.getVersion() : latest.getVersion(),
            eligibilityStatus);
    }

    private String sourceType(SupplierGovernanceQueueKey queue) {
        return switch (queue) {
            case ALL -> "SUPPLIER";
            case NEEDS_ACTION, READY_TO_ACTIVATE, ACTIVE, BLOCKED -> "SUPPLIER";
            case IN_ACCREDITATION, EXPIRING -> "ACCREDITATION";
            case ACCREDITATION_REVIEW, EXPIRING_EVIDENCE -> "ACCREDITATION";
            case KYC_COMPLIANCE -> "KYC";
            case LICENSE_RISK -> "LICENSE";
            case BLACKLIST_UNBLOCK -> "UNBLOCK_REQUEST";
            case OPEN_CAPA -> "CAPA";
            case RECEIVING_BLOCKED -> "PURCHASE_ORDER";
        };
    }

    private String nextAction(SupplierGovernanceQueueKey queue, SupplierAccreditationApplication latest) {
        if (queue == SupplierGovernanceQueueKey.ACCREDITATION_REVIEW && latest != null) {
            return switch (latest.getStatus()) {
                case "SUBMITTED", "NEEDS_CLARIFICATION" -> "START_REVIEW";
                case "UNDER_REVIEW" -> "RUN_COMPLIANCE_CHECK";
                case "COMPLIANCE_CHECK", "COMMITTEE_REVIEW" -> "RECORD_DECISION";
                default -> "OPEN_ACCREDITATION";
            };
        }
        return switch (queue) {
            case ALL -> "REVIEW_SUPPLIER";
            case NEEDS_ACTION -> "RESOLVE_READINESS_ACTION";
            case IN_ACCREDITATION -> "CONTINUE_ACCREDITATION";
            case READY_TO_ACTIVATE -> "ACTIVATE_SUPPLIER";
            case ACTIVE -> "REVIEW_SUPPLIER";
            case BLOCKED -> "REMEDIATE_BLOCKER";
            case EXPIRING -> "RENEW_EVIDENCE";
            case EXPIRING_EVIDENCE -> "RENEW_EVIDENCE";
            case KYC_COMPLIANCE -> "REVIEW_KYC";
            case LICENSE_RISK -> "RENEW_LICENSE";
            case BLACKLIST_UNBLOCK -> "REVIEW_UNBLOCK";
            case OPEN_CAPA -> "REVIEW_CAPA";
            case RECEIVING_BLOCKED -> "RESOLVE_RECEIVING_BLOCKER";
            default -> "OPEN_ACCREDITATION";
        };
    }

    private LocalDate dueDate(
        SupplierGovernanceQueueKey queue,
        SupplierAccreditationApplication latest,
        UUID supplierId
    ) {
        if (queue == SupplierGovernanceQueueKey.EXPIRING_EVIDENCE && latest != null) return latest.getValidTo();
        if (queue == SupplierGovernanceQueueKey.ACCREDITATION_REVIEW && latest != null && latest.getSubmittedAt() != null) {
            return latest.getSubmittedAt().toLocalDate().plusDays(5);
        }
        if (queue == SupplierGovernanceQueueKey.LICENSE_RISK) {
            return licenseRepository.findByTenantIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), supplierId).stream().map(SupplierLicense::getValidTo)
                .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        }
        if (queue == SupplierGovernanceQueueKey.OPEN_CAPA) {
            return correctiveActionRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId).stream()
                .filter(action -> action.getClosedAt() == null).map(SupplierCorrectiveAction::getDueDate)
                .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        }
        return null;
    }

    private Supplier requireSupplier(UUID id) {
        return supplierRepository.findByIdAndIsDeletedFalse(id)
            .filter(supplier -> TenantContext.currentTenant().equals(supplier.getTenantId()))
            .filter(supplier -> Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier governance case not found: " + id));
    }

    private void requireOriginPurchaseOrder(Supplier supplier, UUID purchaseOrderId) {
        purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(po -> TenantContext.currentTenant().equals(po.getTenantId()))
            .filter(po -> Objects.equals(supplier.getCompanyId(), po.getCompanyId()))
            .filter(po -> Objects.equals(supplier.getId(), po.getSupplierId()))
            .orElseThrow(() -> new NotFoundException("Affected purchase order not found: " + purchaseOrderId));
    }

    private List<SupplierAccreditationApplication> applications(UUID supplierId) {
        return applicationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId);
    }

    private List<SupplierAccreditationDecision> decisions(List<SupplierAccreditationApplication> applications) {
        return applications.stream().flatMap(application -> decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()).stream()).toList();
    }

    private List<SupplierAccreditationDocument> documents(List<SupplierAccreditationApplication> applications) {
        return applications.stream().flatMap(application -> documentRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()).stream()).toList();
    }

    private List<PurchaseOrder> purchaseOrders(UUID supplierId) {
        return purchaseOrderRepository.findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId);
    }

    private String firstReceivingBlocker(UUID supplierId) {
        return purchaseOrders(supplierId).stream().filter(this::openPurchaseOrder)
            .map(po -> goodsReceiptBlockedReason(supplierId, po.getId()))
            .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private boolean openPurchaseOrder(PurchaseOrder po) {
        return OPEN_PO_STATUSES.contains(po.getStatus());
    }

    private List<UUID> materialGroupIds(UUID purchaseOrderId) {
        return materialItems(purchaseOrderId).stream()
            .map(PurchaseOrderItem::getMaterial)
            .filter(Objects::nonNull)
            .map(material -> material.getMaterialGroupId())
            .filter(Objects::nonNull)
            .distinct().toList();
    }

    private List<PurchaseOrderItem> materialItems(UUID purchaseOrderId) {
        return purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseOrderId).stream()
            .filter(item -> item.getLineType() == null || item.getLineType() == ProcurementLineType.MATERIAL)
            .toList();
    }

    private String goodsReceiptBlockedReason(UUID supplierId, UUID purchaseOrderId) {
        List<PurchaseOrderItem> items = materialItems(purchaseOrderId);
        if (items.isEmpty()) {
            return null;
        }
        if (items.stream().anyMatch(item ->
            item.getMaterial() == null || item.getMaterial().getMaterialGroupId() == null)) {
            return "Material group is required for every material line in goods receipt";
        }
        return accreditationService.supplierEligibilityBlockedReason(
            supplierId, materialGroupIds(purchaseOrderId), "goods receipt");
    }

    /**
     * UX-LIVE-02: sourced from {@link SupplierAccreditationService#effectiveEligibility} so the
     * dossier's scope matrix and overall status use the exact same dated/scoped projection as
     * the governance list row, the PO dispatch check, and the goods-receipt workspace.
     */
    private ScopeEligibilitySnapshot scopeEligibility(
        UUID supplierId,
        List<SupplierAccreditationApplication> applications,
        List<SupplierAccreditationDecision> decisions,
        UUID originPurchaseOrderId,
        LocalDate asOfDate
    ) {
        LinkedHashMap<UUID, Boolean> groupIds = new LinkedHashMap<>();
        if (originPurchaseOrderId != null) {
            materialGroupIds(originPurchaseOrderId).forEach(id -> groupIds.put(id, true));
        } else {
            applications.stream().map(SupplierAccreditationApplication::getRequestedMaterialGroupIds)
                .filter(Objects::nonNull).flatMap(Collection::stream).forEach(id -> groupIds.put(id, true));
            decisions.stream().map(SupplierAccreditationDecision::getAllowedMaterialGroupIds)
                .filter(Objects::nonNull).flatMap(Collection::stream).forEach(id -> groupIds.put(id, true));
        }
        if (groupIds.isEmpty()) {
            return new ScopeEligibilitySnapshot(List.of(), "UNKNOWN");
        }
        List<UUID> ids = List.copyOf(groupIds.keySet());
        Map<UUID, MaterialGroup> groups = materialGroupRepository
            .findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), ids)
            .stream().collect(Collectors.toMap(MaterialGroup::getId, group -> group));
        EffectiveSupplierEligibility eligibility = accreditationService
            .effectiveEligibility(supplierId, ids, asOfDate, "supplier governance");
        Map<UUID, MaterialGroupEligibility> eligibilityByGroup = eligibility.groups().stream()
            .collect(Collectors.toMap(MaterialGroupEligibility::materialGroupId, row -> row));
        List<ScopeMatrixRow> rows = ids.stream().map(id -> {
            MaterialGroup group = groups.get(id);
            MaterialGroupEligibility row = eligibilityByGroup.get(id);
            String documentType = null;
            String documentCode = null;
            if (row != null) {
                if (row.decisionId() != null) {
                    documentType = "ACCREDITATION_DECISION";
                    documentCode = decisions.stream()
                        .filter(decision -> Objects.equals(decision.getId(), row.decisionId()))
                        .map(SupplierAccreditationDecision::getCode)
                        .findFirst()
                        .orElse(null);
                } else if (row.applicationId() != null) {
                    documentType = "ACCREDITATION_APPLICATION";
                    documentCode = applications.stream()
                        .filter(application -> Objects.equals(application.getId(), row.applicationId()))
                        .map(SupplierAccreditationApplication::getCode)
                        .findFirst()
                        .orElse(null);
                }
            }
            return new ScopeMatrixRow(
                group == null
                    ? new EntityRef(id, shortId(id), "Material group " + shortId(id), null)
                    : ref(group),
                row != null && row.eligible(),
                row == null ? "SUPPLIER_MATERIAL_GROUP_NOT_EVALUATED" : row.reasonCode(),
                row == null ? "Material-group eligibility could not be evaluated" : row.reason(),
                row == null ? null : row.applicationId(),
                row == null ? null : row.decisionId(),
                documentType,
                documentCode,
                row == null ? null : row.validTo(),
                row == null ? null : row.conditions());
        }).toList();
        return new ScopeEligibilitySnapshot(rows, eligibility.overallStatus());
    }

    private record ScopeEligibilitySnapshot(List<ScopeMatrixRow> rows, String overallStatus) {
    }

    private List<AffectedDocumentContext> affectedDocuments(Supplier supplier, List<PurchaseOrder> purchaseOrders) {
        List<AffectedDocumentContext> result = new ArrayList<>();
        for (PurchaseOrder po : purchaseOrders.stream().filter(this::openPurchaseOrder).toList()) {
            String reason = goodsReceiptBlockedReason(supplier.getId(), po.getId());
            result.add(new AffectedDocumentContext(
                "PURCHASE_ORDER", po.getId(), po.getCode(), displayName(po), po.getStatus(),
                reason == null ? null : "SUPPLIER_ACCREDITATION_BLOCKED", reason,
                "/app/purchase-orders/" + po.getId()));
            for (AdvanceShippingNotice asn : asnRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
                    TenantContext.currentTenant(), supplier.getCompanyId(), po.getId())) {
                result.add(new AffectedDocumentContext(
                    "ASN", asn.getId(), asn.getCode(), displayName(asn), asn.getStatus(),
                    reason == null ? null : "SUPPLIER_ACCREDITATION_BLOCKED", reason,
                    "/app/asn?purchaseOrderId=" + po.getId() + "&search=" + asn.getCode()));
            }
        }
        return List.copyOf(result);
    }

    private List<GovernanceAction> allowedActions(
        SupplierAccreditationApplication latest,
        SupplierQualificationResponse qualification,
        SupplierReadinessSummary readiness
    ) {
        boolean roleAllowed = hasAnyRole(MANAGER_ROLES);
        boolean complianceRoleAllowed = hasAnyRole(COMPLIANCE_ACTION_ROLES);
        List<GovernanceAction> actions = new ArrayList<>();
        // Always surface ACTIVATE_SUPPLIER (disabled with a reason when not eligible yet) so the
        // user can see it exists instead of it silently disappearing from the menu.
        boolean activationReady = readiness.activationEligible();
        boolean roleAllowedForActivation = hasAnyRole(MANAGER_ROLES);
        String activationReasonCode = !activationReady ? "NOT_READY_FOR_ACTIVATION"
            : !roleAllowedForActivation ? "ROLE_NOT_ALLOWED" : null;
        actions.add(new GovernanceAction(
            "ACTIVATE_SUPPLIER", "supplierGovernance.action.ACTIVATE_SUPPLIER",
            activationReady && roleAllowedForActivation,
            activationReasonCode,
            "ROLE_NOT_ALLOWED".equals(activationReasonCode) ? Map.of("role", "SUPPLIER_MANAGER") : Map.of(),
            MANAGER_ROLES, true, false,
            readiness.nextAction() == null ? null : readiness.nextAction().applicationId(),
            readiness.nextAction() == null ? null : readiness.nextAction().expectedVersion()));
        if (latest != null) {
            ApprovalInstance instance = activeApprovalInstance(latest.getId(), latest.getCompanyId());
            // While the application is on an active approval instance (DRAFT/PENDING), the
            // decision lives in the generic approval worklist -- suppress SUBMIT_FOR_APPROVAL,
            // APPROVE and REJECT from this drawer so the user is not offered two conflicting paths.
            boolean onApproval = instance != null
                && (instance.getApprovalStatus() == ApprovalInstanceStatus.DRAFT
                    || instance.getApprovalStatus() == ApprovalInstanceStatus.PENDING);
            addAction(actions, "START_REVIEW", latest, List.of("SUBMITTED", "NEEDS_CLARIFICATION"), roleAllowed, false, false);
            addAction(actions, "RUN_COMPLIANCE_CHECK", latest,
                List.of("SUBMITTED", "UNDER_REVIEW", "NEEDS_CLARIFICATION", "COMPLIANCE_CHECK"),
                complianceRoleAllowed, COMPLIANCE_ACTION_ROLES, "COMPLIANCE_MANAGER", false, false);
            addAction(actions, "REQUEST_CLARIFICATION", latest, List.of("SUBMITTED", "UNDER_REVIEW", "COMPLIANCE_CHECK"), roleAllowed, true, false);
            actions.add(submitForApprovalAction(latest, roleAllowed, onApproval));
            addAction(actions, "REJECT", latest, List.of("SUBMITTED", "UNDER_REVIEW", "COMPLIANCE_CHECK", "COMMITTEE_REVIEW", "NEEDS_CLARIFICATION"), roleAllowed && !onApproval, true, false);
            addAction(actions, "SUSPEND", latest, List.of("APPROVED", "CONDITIONALLY_APPROVED"), roleAllowed, true, false);
            addAction(actions, "RENEW", latest, List.of("APPROVED", "CONDITIONALLY_APPROVED", "EXPIRED", "SUSPENDED"), roleAllowed, false, false);
        }
        boolean canRequestUnblock = qualification != null && "BLOCKED".equals(qualification.status().name());
        String unblockReasonCode = !roleAllowed ? "ROLE_NOT_ALLOWED"
            : !canRequestUnblock ? "SUPPLIER_NOT_BLOCKED" : null;
        actions.add(new GovernanceAction(
            "REQUEST_UNBLOCK", "supplierGovernance.action.REQUEST_UNBLOCK",
            roleAllowed && canRequestUnblock,
            unblockReasonCode,
            "ROLE_NOT_ALLOWED".equals(unblockReasonCode) ? Map.of("role", "SUPPLIER_MANAGER") : Map.of(),
            MANAGER_ROLES, true, false,
            latest == null ? null : latest.getId(), latest == null ? null : latest.getVersion()));
        boolean canAddToTenderOrRfq = qualification != null
            && List.of("ACTIVE", "LIMITED").contains(qualification.status().name());
        String addToRfqReasonCode = !roleAllowed ? "ROLE_NOT_ALLOWED"
            : !canAddToTenderOrRfq ? "SUPPLIER_NOT_ACTIVE" : null;
        Map<String, String> addToRfqReasonParams = "ROLE_NOT_ALLOWED".equals(addToRfqReasonCode)
            ? Map.of("role", "SUPPLIER_MANAGER") : Map.of();
        actions.add(new GovernanceAction(
            "ADD_TO_RFQ", "supplierGovernance.action.ADD_TO_RFQ",
            roleAllowed && canAddToTenderOrRfq,
            addToRfqReasonCode, addToRfqReasonParams,
            MANAGER_ROLES, false, false, null, null));
        actions.add(new GovernanceAction(
            "ADD_TO_TENDER", "supplierGovernance.action.ADD_TO_TENDER",
            roleAllowed && canAddToTenderOrRfq,
            addToRfqReasonCode, addToRfqReasonParams,
            MANAGER_ROLES, false, false, null, null));
        return List.copyOf(actions);
    }

    // Unlike the other governance actions, SUBMIT_FOR_APPROVAL routes through the generic approval
    // engine (see ApprovalEngineService.start), which requires an active ApprovalFlow configured for
    // ACCREDITATION_APPROVAL_MODULE_KEY. Without one, the submit call used to blow up with a raw
    // BusinessException after already writing a PROPOSED decision (rolled back by @Transactional,
    // but with no explanation shown to the user) -- this surfaces that as a disabled action with a
    // clear reason instead of a dead end.
    private GovernanceAction submitForApprovalAction(
        SupplierAccreditationApplication application,
        boolean roleAllowed,
        boolean onApproval
    ) {
        boolean statusAllowed = List.of("COMPLIANCE_CHECK", "COMMITTEE_REVIEW").contains(application.getStatus());
        boolean flowConfigured = approvalEngineService.hasActiveFlow(
            ACCREDITATION_APPROVAL_MODULE_KEY, application.getCompanyId(), null);
        String reasonCode = !roleAllowed ? "ROLE_NOT_ALLOWED"
            : onApproval ? "ALREADY_SUBMITTED_FOR_APPROVAL"
            : !statusAllowed ? "STATUS_NOT_ALLOWED"
            : !flowConfigured ? "NO_APPROVAL_FLOW_CONFIGURED" : null;
        if (reasonCode == null) {
            SupplierReadinessSummary applicationReadiness = readinessService.getForApplication(
                application.getSupplierId(), application.getId());
            boolean reviewInputsReady = List.of(
                com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey.EVIDENCE,
                com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey.COMPLIANCE_KYC
            ).stream().allMatch(required -> applicationReadiness.stages().stream().anyMatch(stage ->
                stage.key() == required
                    && stage.state() == com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageState.COMPLETED));
            if (!reviewInputsReady) {
                reasonCode = "REVIEW_PREREQUISITES_NOT_MET";
            }
        }
        Map<String, String> reasonParams = "STATUS_NOT_ALLOWED".equals(reasonCode)
            ? Map.of("status", application.getStatus())
            : "ROLE_NOT_ALLOWED".equals(reasonCode) ? Map.of("role", "SUPPLIER_MANAGER") : Map.of();
        return new GovernanceAction(
            "SUBMIT_FOR_APPROVAL",
            "supplierGovernance.action.SUBMIT_FOR_APPROVAL",
            reasonCode == null,
            reasonCode, reasonParams,
            MANAGER_ROLES, true, false,
            application.getId(), application.getVersion());
    }

    private void addAction(
        List<GovernanceAction> actions,
        String code,
        SupplierAccreditationApplication application,
        List<String> allowedStatuses,
        boolean roleAllowed,
        boolean commentRequired,
        boolean evidenceRequired
    ) {
        addAction(actions, code, application, allowedStatuses, roleAllowed, MANAGER_ROLES, "SUPPLIER_MANAGER",
            commentRequired, evidenceRequired);
    }

    private void addAction(
        List<GovernanceAction> actions,
        String code,
        SupplierAccreditationApplication application,
        List<String> allowedStatuses,
        boolean roleAllowed,
        List<String> requiredRoles,
        String roleHint,
        boolean commentRequired,
        boolean evidenceRequired
    ) {
        boolean statusAllowed = allowedStatuses.contains(application.getStatus());
        String reasonCode = !roleAllowed ? "ROLE_NOT_ALLOWED"
            : !statusAllowed ? "STATUS_NOT_ALLOWED" : null;
        Map<String, String> reasonParams = "STATUS_NOT_ALLOWED".equals(reasonCode)
            ? Map.of("status", application.getStatus())
            : "ROLE_NOT_ALLOWED".equals(reasonCode) ? Map.of("role", roleHint) : Map.of();
        actions.add(new GovernanceAction(
            code,
            "supplierGovernance.action." + code,
            roleAllowed && statusAllowed,
            reasonCode, reasonParams,
            requiredRoles,
            commentRequired,
            evidenceRequired,
            application.getId(),
            application.getVersion()));
    }

    private SupplierHeader header(Supplier supplier) {
        return new SupplierHeader(
            supplier.getId(), supplier.getCode(), displayName(supplier), supplier.getLegalName(),
            supplier.getShortName(), supplier.getRelationshipType(), supplier.getSupplierType(),
            supplier.getOwnerEmployeeId(), supplier.getOwnerEmployee() == null ? null : displayName(supplier.getOwnerEmployee()),
            supplier.getPrimaryContactName(), supplier.getCountryCode(), supplier.getCity(), supplier.getLegalForm(),
            supplier.getStatus(), supplier.getVersion(), supplier.getCategory(), supplier.getRating(),
            supplier.getTaxId(), supplier.getBlockedReason());
    }

    private QualificationContext qualification(SupplierQualificationResponse value) {
        if (value == null) {
            return new QualificationContext("UNKNOWN", null, null, null, null, List.of());
        }
        return new QualificationContext(
            value.status().name(), value.blockedReason(), value.blockedBy(), value.blockedAt(), value.expiresAt(),
            value.restrictions().stream().map(restriction -> new RestrictionContext(
                restriction.type(), restriction.value(), restriction.reason())).toList());
    }

    private AccreditationApplicationContext applicationContext(SupplierAccreditationApplication value) {
        ApprovalInstance instance = activeApprovalInstance(value.getId(), value.getCompanyId());
        return new AccreditationApplicationContext(
            value.getId(), value.getCode(), displayName(value), value.getStatus(), value.getAccreditationType(),
            value.getRequestedScope(), safe(value.getRequestedMaterialGroupIds()), value.getSubmittedAt(),
            value.getValidFrom(), value.getValidTo(), value.getRiskScore(), value.getComplianceRecommendation(),
            value.getReviewComment(), value.getVersion(),
            instance == null ? null : instance.getId(),
            instance == null ? null : instance.getApprovalStatus().name());
    }

    private ApprovalInstance activeApprovalInstance(UUID applicationId, UUID companyId) {
        return approvalInstanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, ACCREDITATION_APPROVAL_MODULE_KEY, applicationId
        ).orElse(null);
    }

    private AccreditationDecisionContext decisionContext(SupplierAccreditationDecision value) {
        return new AccreditationDecisionContext(
            value.getId(), value.getApplicationId(), value.getCode(), value.getDecision(), value.getStatus(), value.getApprovedScope(),
            safe(value.getAllowedMaterialGroupIds()), value.getValidFrom(), value.getValidTo(), value.getConditions(),
            value.getCommissionProtocolNo(), value.getCreatedAt());
    }

    private EvidenceContext evidenceContext(SupplierAccreditationDocument value) {
        return new EvidenceContext(
            value.getId(), value.getApplicationId(), value.getCode(), displayName(value), value.getDocumentType(), value.getAttachmentId(),
            value.getStatus(), value.getValidFrom(), value.getValidTo(), expiryUrgency(value.getValidTo()),
            value.getVerifiedBy(), value.getVerifiedAt(), value.getVersion());
    }

    private LicenseContext licenseContext(SupplierLicense value) {
        return new LicenseContext(
            value.getId(), value.getCode(), displayName(value), value.getStatus(), value.getLicenseNumber(),
            value.getLicenseType(), value.getValidTo(), value.isRenewalRequired(), value.getRevokedAt(),
            value.getRevocationReason());
    }

    private KycContext kycContext(SupplierKycCheck value) {
        return new KycContext(
            value.getId(), value.getCode(), displayName(value), value.getStatus(), value.getCheckType(),
            value.getRiskLevel(), value.getScore(), value.getCheckedAt(), value.getValidUntil(),
            value.getNextReviewDate(), value.isRemediationRequired(), value.getFindings());
    }

    private CapaContext capaContext(SupplierCorrectiveAction value) {
        return new CapaContext(
            value.getId(), value.getCode(), displayName(value), value.getStatus(), value.getSourceType(),
            value.getSourceId(), value.getIssueTitle(), value.getSeverity(), value.getOwnerEmployeeId(),
            value.getDueDate(), value.getSubmittedAt(), value.getVerifiedAt(), value.getClosedAt(),
            value.getVerificationResult());
    }

    private UnblockRequestContext unblockContext(SupplierUnblockRequest value) {
        return new UnblockRequestContext(
            value.getId(), value.getCode(), value.getStatus(), value.getReason(), value.getPurchaseOrderId(),
            value.getRequestedBy(), value.getReviewedBy(), value.getReviewedAt(), value.getReviewComment(),
            value.getDecidedBy(), value.getDecidedAt(), value.getDecisionComment(), value.getVersion(),
            value.getCreatedAt());
    }

    private List<AuditContext> auditTimeline(
        Supplier supplier,
        List<SupplierAccreditationApplication> applications,
        List<SupplierAccreditationDocument> documents,
        List<SupplierAccreditationDecision> decisions,
        List<SupplierActivationRecord> activationRecords,
        List<SupplierCorrectiveAction> correctiveActions,
        List<SupplierUnblockRequest> unblockRequests
    ) {
        List<AuditContext> result = new ArrayList<>();
        appendAudit(result, supplier.getCompanyId(), "Supplier", supplier.getId(), supplier.getCode());
        applications.forEach(value -> appendAudit(
            result, supplier.getCompanyId(), "SupplierAccreditationApplication", value.getId(), value.getCode()));
        documents.forEach(value -> appendAudit(
            result, supplier.getCompanyId(), "SupplierAccreditationDocument", value.getId(), value.getCode()));
        decisions.forEach(value -> appendAudit(
            result, supplier.getCompanyId(), "SupplierAccreditationDecision", value.getId(), value.getCode()));
        activationRecords.forEach(value -> appendAudit(
            result, supplier.getCompanyId(), "SupplierActivationRecord", value.getId(), value.getCode()));
        correctiveActions.forEach(value -> appendAudit(
            result, supplier.getCompanyId(), "SupplierCorrectiveAction", value.getId(), value.getCode()));
        unblockRequests.forEach(value -> appendAudit(
            result, supplier.getCompanyId(), "SupplierUnblockRequest", value.getId(), value.getCode()));
        return result.stream().sorted(java.util.Comparator.comparing(
            AuditContext::occurredAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
    }

    private List<ActivityEventContext> activityTimeline(List<AuditContext> audits) {
        return audits.stream()
            .map(audit -> new ActivityEventContext(
                audit.id(),
                audit.action(),
                "supplierGovernance.activity." + safeCode(audit.action()),
                audit.actor(),
                null,
                "SUPPLIER",
                null,
                audit.action(),
                safeSummary(audit.details()),
                null,
                audit.occurredAt()
            ))
            .toList();
    }

    private String safeCode(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String safeSummary(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value
            .replaceAll("\\{.*?}", "")
            .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "")
            .replace("tenantId", "")
            .replace("companyId", "")
            .trim();
        return result.isBlank() ? null : result;
    }

    private void appendAudit(
        List<AuditContext> target,
        UUID companyId,
        String entityType,
        UUID entityId,
        String documentCode
    ) {
        auditLogRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
            TenantContext.currentTenant(), companyId, entityType, entityId).stream()
            .map(log -> new AuditContext(
                log.getId(), log.getAction(), systemActor(log.getFullName()),
                documentCode == null ? log.getDetails() : documentCode + (log.getDetails() == null ? "" : " В· " + log.getDetails()),
                log.getOccurredAt()))
            .forEach(target::add);
    }

    private String systemActor(String actor) {
        return actor == null || actor.isBlank() || "system".equalsIgnoreCase(actor)
            ? "SYSTEM"
            : actor;
    }

    private List<IntegrationContext> integrationImpact(Supplier supplier, List<PurchaseOrder> purchaseOrders) {
        Map<UUID, IntegrationContext> result = new LinkedHashMap<>();
        List<UUID> aggregateIds = new ArrayList<>();
        aggregateIds.add(supplier.getId());
        aggregateIds.addAll(purchaseOrders.stream().map(PurchaseOrder::getId).toList());
        for (UUID aggregateId : aggregateIds) {
            integrationEventRepository.findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), supplier.getCompanyId(), aggregateId).forEach(event -> result.putIfAbsent(
                    event.getId(), new IntegrationContext(event.getId(), event.getEventType(), event.getStatus(),
                        event.getRetryCount(), event.getLastError(), event.getCreatedAt())));
        }
        return List.copyOf(result.values());
    }

    private String qualificationBlocker(SupplierQualificationResponse qualification) {
        if (qualification == null) return null;
        return switch (qualification.status()) {
            case BLOCKED -> "SUPPLIER_BLOCKED";
            case EXPIRED -> "SUPPLIER_ACCREDITATION_EXPIRED";
            case LIMITED -> "SUPPLIER_ACCREDITATION_LIMITED";
            default -> null;
        };
    }

    private String expiryUrgency(LocalDate validTo) {
        if (validTo == null) return "NO_EXPIRY";
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), validTo);
        if (days < 0) return "EXPIRED";
        if (days <= 7) return "CRITICAL";
        if (days <= 30) return "DUE_SOON";
        return "VALID";
    }

    private boolean currentCompany(com.enterprise.mm.common.BusinessEntity entity) {
        return AccessScope.canAccessCompany(entity.getCompanyId());
    }

    private boolean hasAnyRole(List<String> roles) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return false;
        Set<String> authorities = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority()).collect(Collectors.toSet());
        return roles.stream().map(role -> "ROLE_" + role).anyMatch(authorities::contains);
    }

    private String dataScope() {
        return AccessScope.companyScoped(true) ? "COMPANY" : "TENANT";
    }

    private EntityRef ref(com.enterprise.mm.common.BusinessEntity entity) {
        return new EntityRef(entity.getId(), entity.getCode(), displayName(entity), entity.getStatus());
    }

    private String displayName(com.enterprise.mm.common.BusinessEntity entity) {
        if (entity.getName() != null && !entity.getName().isBlank()) return entity.getName();
        if (entity instanceof Supplier supplier && supplier.getLegalName() != null && !supplier.getLegalName().isBlank()) {
            return supplier.getLegalName();
        }
        return entity.getCode() == null ? shortId(entity.getId()) : entity.getCode();
    }

    private String shortId(UUID id) {
        return id == null ? "вЂ”" : id.toString().substring(0, 8);
    }

    private <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}

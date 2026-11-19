package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierAccreditationDocument;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.enums.SupplierStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierAccreditationApplicationMapper;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierAccreditationApplicationCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationApplicationResponse;
import com.enterprise.mm.payload.SupplierAccreditationApplicationUpdateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDecisionActionRequest;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.payload.SupplierAccreditationSearchRequest;
import com.enterprise.mm.payload.SupplierEligibilityDtos.MaterialGroupEligibility;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.payload.SupplierEligibilityDtos.EffectiveSupplierEligibility;
import com.enterprise.mm.payload.SupplierEligibilityDtos.BlockingFactor;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.supplier.governance.readiness.SupplierEvidenceChecklistService;
import com.enterprise.mm.util.BusinessNameGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierAccreditationService {
    private static final Set<String> ELIGIBLE_STATUSES = Set.of(
        SupplierAccreditationStatus.APPROVED.name(),
        SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name()
    );
    private static final Set<String> SERVICE_ACCREDITATION_TYPES = Set.of("SERVICE", "SERVICES");
    private static final Set<String> WORKS_ACCREDITATION_TYPES = Set.of("WORK", "WORKS");
    private static final Set<String> MATERIAL_ACCREDITATION_TYPES = Set.of(
        "GOODS", "MATERIAL", "MATERIAL_GROUP"
    );
    private static final String APPROVAL_MODULE_KEY = "SUPPLIER_ACCREDITATION";
    private static final String DECISION_STATUS_PROPOSED = "PROPOSED";
    private static final String DECISION_STATUS_RECORDED = "RECORDED";
    private static final String DECISION_STATUS_CANCELLED = "CANCELLED";
    // UX-LIVE-02: distinct blocker codes so the UI can point suppliers/buyers at the right fix
    // instead of a single generic "not eligible" reason.
    private static final String REASON_SUPPLIER_BLACKLISTED = "SUPPLIER_BLACKLISTED";
    private static final String REASON_SUPPLIER_COMPLIANCE_BLOCKED = "SUPPLIER_COMPLIANCE_BLOCKED";
    private static final String REASON_SUPPLIER_EVIDENCE_MISSING = "SUPPLIER_EVIDENCE_MISSING";
    private static final String REASON_SUPPLIER_ACCREDITATION_EXPIRED = "SUPPLIER_ACCREDITATION_EXPIRED";
    private static final String REASON_SUPPLIER_ACCREDITATION_DECISION_PENDING = "SUPPLIER_ACCREDITATION_DECISION_PENDING";
    private static final String REASON_SUPPLIER_MATERIAL_GROUP_NOT_COVERED = "SUPPLIER_MATERIAL_GROUP_NOT_COVERED";

    private final SupplierAccreditationApplicationRepository repository;
    private final SupplierAccreditationDecisionRepository decisionRepository;
    private final SupplierAccreditationDocumentRepository documentRepository;
    private final MaterialGroupRepository materialGroupRepository;
    private final SupplierAccreditationEvidencePolicy evidencePolicy;
    private final SupplierEvidenceChecklistService checklistService;
    private final SupplierAccreditationApplicationMapper mapper;
    private final SupplierComplianceService complianceService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    // Injected directly (not via ApprovalEngineService) to avoid a circular bean dependency:
    // ApprovalEngineService -> ApprovalModuleRegistry -> SupplierAccreditationApprovalAdapter -> SupplierAccreditationService.
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy supplierGovernanceActionPolicy;
    private final String entityName = "SupplierAccreditationApplication";

    @Transactional(readOnly = true)
    public Page<SupplierAccreditationApplicationResponse> search(SupplierAccreditationSearchRequest request, Pageable pageable) {
        SupplierAccreditationSearchRequest criteria = request == null
            ? SupplierAccreditationSearchRequest.empty()
            : request;
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.findAll(applicationSearchSpec(criteria, scoped), pageable).map(this::toResponse);
    }


    @Transactional(readOnly = true)
    public SupplierAccreditationApplicationResponse get(UUID id) {
        return toResponse(findEntity(id));
    }

    @Transactional
    public SupplierAccreditationApplicationResponse create(SupplierAccreditationApplicationCreateRequest dto) {
        if (dto == null) {
            throw new BadRequestException("Supplier accreditation request is required");
        }
        SupplierAccreditationApplication entity = mapper.toEntity(dto);
        beforeCreate(entity);
        SupplierAccreditationApplication saved = repository.saveAndFlush(entity);
        checklistService.snapshotFor(saved);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse update(UUID id, SupplierAccreditationApplicationUpdateRequest dto) {
        if (dto == null) {
            throw new BadRequestException("Supplier accreditation request is required");
        }
        SupplierAccreditationApplication entity = findMutableEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        ensureEditable(entity);
        UUID supplierId = entity.getSupplierId();
        mapper.updateEntity(entity, dto);
        entity.setSupplierId(supplierId);
        beforeUpdate(entity);
        SupplierAccreditationApplication saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse submit(UUID id) {
        SupplierAccreditationApplication entity = findMutableEntity(id);
        ensureStatus(entity, SupplierAccreditationStatus.DRAFT);
        entity.setStatus(SupplierAccreditationStatus.SUBMITTED.name());
        entity.setSubmittedAt(LocalDateTime.now());
        SupplierAccreditationApplication saved = repository.save(entity);
        auditService.log(entityName, id, "SUBMIT", entity.getCode(), null, null);
        return toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse startReview(UUID id) {
        SupplierAccreditationApplication entity = findMutableEntity(id);
        // UX-LIVE-04: enforce START_REVIEW action policy
        supplierGovernanceActionPolicy.requireActionAllowed(entity, "START_REVIEW");
        ensureStatus(entity, SupplierAccreditationStatus.SUBMITTED, SupplierAccreditationStatus.NEEDS_CLARIFICATION, SupplierAccreditationStatus.COMPLIANCE_CHECK);
        entity.setStatus(SupplierAccreditationStatus.UNDER_REVIEW.name());
        if (entity.getReviewStartedAt() == null) {
            entity.setReviewStartedAt(LocalDateTime.now());
        }
        SupplierAccreditationApplication saved = repository.save(entity);
        auditService.log(entityName, id, "START_REVIEW", entity.getCode(), null, null);
        return toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse requestClarification(UUID id, String comment) {
        SupplierAccreditationApplication entity = findMutableEntity(id);
        // UX-LIVE-04: enforce REQUEST_CLARIFICATION action policy
        supplierGovernanceActionPolicy.requireActionAllowed(entity, "REQUEST_CLARIFICATION");
        ensureStatus(entity, SupplierAccreditationStatus.SUBMITTED, SupplierAccreditationStatus.UNDER_REVIEW, SupplierAccreditationStatus.COMPLIANCE_CHECK);
        entity.setStatus(SupplierAccreditationStatus.NEEDS_CLARIFICATION.name());
        entity.setReviewComment(comment);
        SupplierAccreditationApplication saved = repository.save(entity);
        auditService.log(entityName, id, "REQUEST_CLARIFICATION", comment, null, null);
        return toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse runComplianceCheck(UUID id) {
        SupplierAccreditationApplication entity = findMutableEntity(id);
        // UX-LIVE-04: enforce RUN_COMPLIANCE_CHECK action policy
        supplierGovernanceActionPolicy.requireActionAllowed(entity, "RUN_COMPLIANCE_CHECK");
        ensureStatus(entity, SupplierAccreditationStatus.SUBMITTED, SupplierAccreditationStatus.UNDER_REVIEW, SupplierAccreditationStatus.NEEDS_CLARIFICATION, SupplierAccreditationStatus.COMPLIANCE_CHECK);
        SupplierComplianceSummary summary = complianceService.summarize(entity.getSupplierId());
        entity.setStatus(SupplierAccreditationStatus.COMPLIANCE_CHECK.name());
        entity.setComplianceRecommendation(summary.recommendedStatus());
        entity.setRiskScore(calculateRiskScore(summary));
        entity.setComplianceSnapshotJson(toJson(summary));
        SupplierAccreditationApplication saved = repository.save(entity);
        complianceService.applySupplierRisk(entity.getSupplierId());
        auditService.log(entityName, id, "RUN_COMPLIANCE_CHECK", summary.recommendedStatus(), null, null);
        return toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse approve(UUID id, SupplierAccreditationDecisionActionRequest request) {
        // Applications created before the approval-engine integration have no ApprovalInstance and
        // keep using direct approve/reject; new submissions must go through submit-for-approval.
        if (hasActiveApprovalInstance(id)) {
            throw new BusinessException("This application is routed through the approval workflow; use the approval task instead of direct approve/reject");
        }
        SupplierAccreditationApplication entity = findMutableEntity(id);
        ensureStatus(entity, SupplierAccreditationStatus.COMPLIANCE_CHECK, SupplierAccreditationStatus.COMMITTEE_REVIEW);
        if (entity.getComplianceRecommendation() == null || entity.getComplianceRecommendation().isBlank()
            || entity.getComplianceSnapshotJson() == null || entity.getComplianceSnapshotJson().isBlank()) {
            throw new BusinessException("Completed compliance check is required before accreditation approval");
        }
        String operationalBlock = complianceService.operationalBlockReason(
            entity.getSupplierId(), "supplier accreditation approval");
        if (operationalBlock != null) {
            throw new BusinessException(operationalBlock);
        }
        SupplierComplianceSummary summary = complianceService.summarize(entity.getSupplierId());
        entity.setComplianceRecommendation(summary.recommendedStatus());
        entity.setRiskScore(calculateRiskScore(summary));
        entity.setComplianceSnapshotJson(toJson(summary));
        if (SupplierStatus.BLOCKED.name().equals(summary.recommendedStatus())) {
            throw new BusinessException("Blocked supplier cannot be accredited");
        }
        validateApprovalReadiness(entity, request);
        String finalStatus = normalizeApprovalStatus(request.decision(), summary.recommendedStatus());
        LocalDate validFrom = approvalValidFrom(entity, request);
        LocalDate validTo = approvalValidTo(entity, request);
        entity.setStatus(finalStatus);
        entity.setValidFrom(validFrom);
        entity.setValidTo(validTo);
        entity.setReviewComment(request.reviewComment());
        saveDecision(entity, request, finalStatus);
        SupplierAccreditationApplication saved = repository.save(entity);
        integrationEventService.emit(
            "SUPPLIER_ACCREDITATION_APPROVED",
            "SupplierAccreditationApplication",
            saved.getId(),
            Map.of(
                "applicationId", saved.getId(),
                "supplierId", saved.getSupplierId(),
                "status", saved.getStatus()
            )
        );
        auditService.log(entityName, id, "APPROVE", saved.getStatus(), null, null);
        return toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse reject(UUID id, SupplierAccreditationDecisionActionRequest request) {
        // Applications created before the approval-engine integration have no ApprovalInstance and
        // keep using direct approve/reject; new submissions must go through submit-for-approval.
        if (hasActiveApprovalInstance(id)) {
            throw new BusinessException("This application is routed through the approval workflow; use the approval task instead of direct approve/reject");
        }
        SupplierAccreditationApplication entity = findMutableEntity(id);
        // UX-LIVE-04: enforce REJECT action policy
        supplierGovernanceActionPolicy.requireActionAllowed(entity, "REJECT");
        ensureStatus(entity, SupplierAccreditationStatus.SUBMITTED, SupplierAccreditationStatus.UNDER_REVIEW, SupplierAccreditationStatus.COMPLIANCE_CHECK, SupplierAccreditationStatus.COMMITTEE_REVIEW, SupplierAccreditationStatus.NEEDS_CLARIFICATION);
        entity.setStatus(SupplierAccreditationStatus.REJECTED.name());
        entity.setReviewComment(request == null ? null : request.reviewComment());
        saveDecision(entity, request, SupplierAccreditationStatus.REJECTED.name());
        SupplierAccreditationApplication saved = repository.save(entity);
        auditService.log(entityName, id, "REJECT", saved.getReviewComment(), null, null);
        return toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse suspend(UUID id, String comment) {
        SupplierAccreditationApplication entity = findMutableEntity(id);
        // UX-LIVE-04: enforce SUSPEND action policy
        supplierGovernanceActionPolicy.requireActionAllowed(entity, "SUSPEND");
        ensureStatus(entity, SupplierAccreditationStatus.APPROVED, SupplierAccreditationStatus.CONDITIONALLY_APPROVED);
        entity.setStatus(SupplierAccreditationStatus.SUSPENDED.name());
        entity.setReviewComment(comment);
        SupplierAccreditationApplication saved = repository.save(entity);
        auditService.log(entityName, id, "SUSPEND", comment, null, null);
        return toResponse(saved);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse renew(UUID id) {
        SupplierAccreditationApplication entity = findMutableEntity(id);
        // UX-LIVE-04: enforce RENEW action policy
        supplierGovernanceActionPolicy.requireActionAllowed(entity, "RENEW");
        ensureStatus(entity, SupplierAccreditationStatus.APPROVED, SupplierAccreditationStatus.CONDITIONALLY_APPROVED, SupplierAccreditationStatus.EXPIRED, SupplierAccreditationStatus.SUSPENDED);
        SupplierAccreditationApplication renewal = new SupplierAccreditationApplication();
        renewal.setSupplierId(entity.getSupplierId());
        renewal.setAccreditationType(entity.getAccreditationType());
        renewal.setRequestedScope(entity.getRequestedScope());
        renewal.setRequestedMaterialGroupIds(entity.getRequestedMaterialGroupIds());
        renewal.setParentApplicationId(entity.getId());
        renewal.setDescription(entity.getDescription());
        renewal.setName(BusinessNameGenerator.workflowDocumentName("SupplierAccreditationRenewal", entity.getName()));
        renewal.setValidFrom(null);
        renewal.setValidTo(null);
        renewal.setCompanyId(entity.getCompanyId());
        beforeCreate(renewal);
        SupplierAccreditationApplication saved = repository.saveAndFlush(renewal);
        checklistService.snapshotFor(saved);
        auditService.log(entityName, id, "RENEW", saved.getCode(), null, null);
        return toResponse(saved);
    }

    /**
     * Task 5: records the initiator's decision proposal and leaves the application's business
     * status untouched (still COMPLIANCE_CHECK/COMMITTEE_REVIEW). The caller
     * ({@link com.enterprise.mm.service.SupplierAccreditationCommandService#submitForApproval}) immediately follows this
     * with {@code ApprovalEngineService.start(...)}, whose adapter callback
     * ({@link com.enterprise.mm.service.approval.SupplierAccreditationApprovalAdapter#markSubmitted}) is what actually flips the
     * application status to PENDING_APPROVAL.
     */
    @Transactional
    public SupplierAccreditationApplicationResponse proposeDecision(UUID id, SupplierAccreditationDecisionActionRequest request) {
        SupplierAccreditationApplication entity = findMutableEntity(id);
        ensureStatus(entity, SupplierAccreditationStatus.COMPLIANCE_CHECK, SupplierAccreditationStatus.COMMITTEE_REVIEW);
        if (decisionRepository.findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), entity.getCompanyId(), entity.getId(), DECISION_STATUS_PROPOSED).isPresent()) {
            throw new BusinessException("Заявка уже отправлена на согласование");
        }
        String operationalBlock = complianceService.operationalBlockReason(
            entity.getSupplierId(), "supplier accreditation approval");
        if (operationalBlock != null) {
            throw new BusinessException(operationalBlock);
        }
        SupplierComplianceSummary summary = complianceService.summarize(entity.getSupplierId());
        entity.setComplianceRecommendation(summary.recommendedStatus());
        entity.setRiskScore(calculateRiskScore(summary));
        entity.setComplianceSnapshotJson(toJson(summary));
        if (SupplierStatus.BLOCKED.name().equals(summary.recommendedStatus())) {
            throw new BusinessException("Blocked supplier cannot be accredited");
        }
        validateApprovalReadiness(entity, request);
        String finalStatus = normalizeApprovalStatus(request.decision(), summary.recommendedStatus());
        repository.save(entity);

        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setApplicationId(entity.getId());
        decision.setCompanyId(entity.getCompanyId());
        decision.setCode("ACC-DEC-" + UUID.randomUUID().toString().substring(0, 8));
        decision.setName(BusinessNameGenerator.workflowDocumentName("SupplierAccreditationDecision", entity.getName()));
        decision.setStatus(DECISION_STATUS_PROPOSED);
        decision.setDecision(finalStatus);
        decision.setApprovedScope(entity.getRequestedScope());
        decision.setAllowedMaterialGroupIds(approvedMaterialGroupIds(entity, request));
        decision.setValidFrom(approvalValidFrom(entity, request));
        decision.setValidTo(approvalValidTo(entity, request));
        decision.setConditions(null);
        decision.setCommissionProtocolNo(null);
        decision.setReviewComment(request.reviewComment());
        decisionRepository.save(decision);

        return toResponse(entity);
    }

    /** Called only by {@link com.enterprise.mm.service.approval.SupplierAccreditationApprovalAdapter#markApproved}. */
    @Transactional
    public SupplierAccreditationApplicationResponse finalizeApprovedDecision(UUID applicationId, String approverComment) {
        SupplierAccreditationApplication entity = findMutableEntity(applicationId);
        ensureStatus(entity, SupplierAccreditationStatus.PENDING_APPROVAL);
        SupplierAccreditationDecision decision = decisionRepository
            .findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), entity.getCompanyId(), entity.getId(), DECISION_STATUS_PROPOSED)
            .orElseThrow(() -> new BusinessException("No proposed accreditation decision found for application " + applicationId));
        // Safety re-check: compliance state can change while the application sat in the approval
        // queue. If this throws, the exception propagates through the adapter call inside
        // ApprovalEngineService.approveTask()'s @Transactional method, rolling back the whole
        // approve-task call (task/instance stay PENDING). The approver just sees an error and can
        // retry later once the underlying compliance issue is resolved.
        String operationalBlock = complianceService.operationalBlockReason(
            entity.getSupplierId(), "supplier accreditation approval");
        if (operationalBlock != null) {
            throw new BusinessException(operationalBlock);
        }
        entity.setStatus(decision.getDecision());
        entity.setValidFrom(decision.getValidFrom());
        entity.setValidTo(decision.getValidTo());
        entity.setReviewComment(approverComment);
        decision.setStatus(DECISION_STATUS_RECORDED);
        decisionRepository.save(decision);
        SupplierAccreditationApplication saved = repository.save(entity);
        integrationEventService.emit(
            "SUPPLIER_ACCREDITATION_APPROVED",
            "SupplierAccreditationApplication",
            saved.getId(),
            Map.of(
                "applicationId", saved.getId(),
                "supplierId", saved.getSupplierId(),
                "status", saved.getStatus()
            )
        );
        auditService.log(entityName, applicationId, "APPROVE", saved.getStatus(), null, null);
        return toResponse(saved);
    }

    /** Called only by {@link com.enterprise.mm.service.approval.SupplierAccreditationApprovalAdapter#markRejected}. */
    @Transactional
    public SupplierAccreditationApplicationResponse cancelProposedDecision(UUID applicationId, String approverComment) {
        SupplierAccreditationApplication entity = findMutableEntity(applicationId);
        SupplierAccreditationDecision decision = decisionRepository
            .findFirstByTenantIdAndCompanyIdAndApplicationIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), entity.getCompanyId(), entity.getId(), DECISION_STATUS_PROPOSED)
            .orElse(null);
        entity.setStatus(SupplierAccreditationStatus.REJECTED.name());
        entity.setReviewComment(approverComment);
        if (decision != null) {
            decision.setStatus(DECISION_STATUS_CANCELLED);
            decisionRepository.save(decision);
        }
        SupplierAccreditationApplication saved = repository.save(entity);
        auditService.log(entityName, applicationId, "REJECT", entity.getReviewComment(), null, null);
        return toResponse(saved);
    }

    /**
     * Present regardless of {@code ApprovalInstance.approvalStatus} (DRAFT/PENDING/APPROVED/REJECTED
     * all count as "this application has been routed through the engine at least once").
     */
    private boolean hasActiveApprovalInstance(UUID applicationId) {
        return approvalInstanceRepository.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), APPROVAL_MODULE_KEY, applicationId
        ).isPresent();
    }

    @Transactional
    public void softDelete(UUID id) {
        SupplierAccreditationApplication entity = findMutableEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        ensureEditable(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    @Transactional
    public SupplierAccreditationApplicationResponse restore(UUID id) {
        SupplierAccreditationApplication entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateTenantCompanyScope(entity);
        requireCurrentCompany(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        ensureEditable(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierAccreditationApplication saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public SupplierAccreditationApplication validateApplication(UUID id) {
        return findEntity(id);
    }

    @Transactional(readOnly = true)
    public SupplierAccreditationApplication validateApplicationForMutation(UUID id) {
        return findMutableEntity(id);
    }

    @Transactional(readOnly = true)
    public void assertSupplierEligible(UUID supplierId, Collection<UUID> materialGroupIds, String source) {
        assertSupplierEligibleForDemand(supplierId, AccreditationDemand.materials(materialGroupIds), source);
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public void assertSupplierEligibleForDemand(UUID supplierId, AccreditationDemand demand, String source) {
        String blockedReason = supplierEligibilityBlockedReasonForDemand(supplierId, demand, source);
        if (blockedReason != null) {
            throw new BusinessException(blockedReason);
        }
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public String supplierEligibilityBlockedReason(UUID supplierId, Collection<UUID> materialGroupIds, String source) {
        return supplierEligibilityBlockedReasonForDemand(supplierId, AccreditationDemand.materials(materialGroupIds), source);
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public String supplierEligibilityBlockedReasonForDemand(UUID supplierId, AccreditationDemand demand, String source) {
        return supplierEligibilityBlockedReasonForDemand(supplierId, demand, source, LocalDate.now());
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public String supplierEligibilityBlockedReasonForDemand(
        UUID supplierId,
        AccreditationDemand demand,
        String source,
        LocalDate asOfDate
    ) {
        if (demand == null
            || (!demand.materialRequired() && !demand.serviceRequired() && !demand.worksRequired())) {
            return "Accreditation scope is required for " + source;
        }
        if (demand.materialRequired()
            && (!demand.materialScopeComplete() || demand.materialGroupIds().isEmpty())) {
            return demand.materialScopeComplete()
                ? "Supplier accreditation is required for " + source
                : "Material group is required for every material line in " + source;
        }
        try {
            String operationalBlock = complianceService.operationalBlockReason(supplierId, source);
            if (operationalBlock != null) {
                return operationalBlock;
            }
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
        List<SupplierAccreditationApplication> applications = repository.findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            supplierId,
            ELIGIBLE_STATUSES
        );
        LocalDate today = asOfDate == null ? LocalDate.now() : asOfDate;
        List<EffectiveAccreditation> effectiveAccreditations = applications.stream()
            .map(application -> new EffectiveAccreditation(application, effectiveDecisionBase(application, today)))
            .filter(effective -> effective.decision() != null)
            .toList();
        if (demand.materialRequired()) {
            MaterialGroupEligibility missingGroup = materialGroupEligibility(
                    supplierId,
                    demand.materialGroupIds(),
                    source,
                    today
                ).stream()
                .filter(group -> !group.eligible())
                .findFirst()
                .orElse(null);
            if (missingGroup != null) {
                return missingGroup.reason() == null || missingGroup.reason().isBlank()
                    ? "Supplier accreditation is required for " + source
                    : missingGroup.reason();
            }
        }
        if (demand.serviceRequired() && !hasTypedScope(effectiveAccreditations, SERVICE_ACCREDITATION_TYPES)) {
            return "Supplier service accreditation is required for " + source;
        }
        if (demand.worksRequired() && !hasTypedScope(effectiveAccreditations, WORKS_ACCREDITATION_TYPES)) {
            return "Supplier works accreditation is required for " + source;
        }
        return null;
    }

    @Transactional(readOnly = true)
    public void assertSupplierServiceEligible(UUID supplierId, String source) {
        assertSupplierEligibleForDemand(supplierId, AccreditationDemand.services(), source);
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public String supplierServiceEligibilityBlockedReason(UUID supplierId, String source) {
        return supplierEligibilityBlockedReasonForDemand(supplierId, AccreditationDemand.services(), source);
    }

    @Transactional(readOnly = true)
    public void assertSupplierWorksEligible(UUID supplierId, String source) {
        assertSupplierEligibleForDemand(supplierId, AccreditationDemand.works(), source);
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public String supplierWorksEligibilityBlockedReason(UUID supplierId, String source) {
        return supplierEligibilityBlockedReasonForDemand(supplierId, AccreditationDemand.works(), source);
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public List<MaterialGroupEligibility> materialGroupEligibility(
        UUID supplierId,
        Collection<UUID> materialGroupIds,
        String source
    ) {
        return materialGroupEligibility(supplierId, materialGroupIds, source, LocalDate.now());
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public List<MaterialGroupEligibility> materialGroupEligibility(
        UUID supplierId,
        Collection<UUID> materialGroupIds,
        String source,
        LocalDate asOfDate
    ) {
        List<UUID> requestedGroups = materialGroupIds == null
            ? List.of()
            : materialGroupIds.stream().filter(Objects::nonNull).distinct().toList();
        if (requestedGroups.isEmpty()) {
            return List.of();
        }
        String complianceReason = null;
        try {
            complianceReason = complianceService.operationalBlockReason(supplierId, source);
        } catch (BusinessException ex) {
            complianceReason = ex.getMessage();
        }
        if (complianceReason != null) {
            String reason = complianceReason;
            String reasonCode = isBlacklistReason(reason)
                ? REASON_SUPPLIER_BLACKLISTED
                : REASON_SUPPLIER_COMPLIANCE_BLOCKED;
            return requestedGroups.stream().map(groupId -> new MaterialGroupEligibility(
                groupId, false, reasonCode, reason,
                null, null, null, null, null
            )).toList();
        }

        List<SupplierAccreditationApplication> applications = repository
            .findByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId, ELIGIBLE_STATUSES
            );
        LocalDate today = asOfDate == null ? LocalDate.now() : asOfDate;
        return requestedGroups.stream()
            .map(groupId -> materialGroupEligibility(groupId, applications, today, source))
            .toList();
    }

    /**
     * UX-LIVE-02: single dated/scoped eligibility snapshot for a supplier, shared by the list,
     * dossier, PO, and receipt surfaces so they never disagree on "eligible" vs "partially
     * eligible" vs "not eligible" for the same supplier and date.
     */
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public EffectiveSupplierEligibility effectiveEligibility(
        UUID supplierId,
        Collection<UUID> materialGroupIds,
        LocalDate asOfDate,
        String source
    ) {
        LocalDate effectiveDate = asOfDate == null ? LocalDate.now() : asOfDate;
        List<UUID> requestedGroups = normalizeMaterialGroupIds(materialGroupIds);
        List<UUID> evaluatedGroups = requestedGroups.isEmpty()
            ? knownMaterialGroupScopes(supplierId)
            : requestedGroups;
        if (evaluatedGroups.isEmpty()) {
            return new EffectiveSupplierEligibility(
                supplierId,
                effectiveDate,
                "UNKNOWN",
                false,
                List.of(),
                List.of(),
                "No accreditation scope is known for this supplier"
            );
        }
        List<MaterialGroupEligibility> groups = materialGroupEligibility(supplierId, evaluatedGroups, source, effectiveDate);
        long eligibleCount = groups.stream().filter(MaterialGroupEligibility::eligible).count();
        String overallStatus = eligibleCount == 0
            ? "NOT_ELIGIBLE"
            : eligibleCount == groups.size() ? "ELIGIBLE" : "PARTIALLY_ELIGIBLE";
        boolean eligibleForRequestedScope = "ELIGIBLE".equals(overallStatus);
        Map<UUID, String> materialGroupCodes = materialGroupCodes(groups);
        List<BlockingFactor> blockers = groups.stream()
            .filter(group -> !group.eligible())
            .map(group -> new BlockingFactor(
                group.reasonCode(),
                group.materialGroupId(),
                materialGroupCodes.get(group.materialGroupId()),
                documentTypeFor(group),
                documentIdFor(group),
                documentCodeFor(group),
                group.reason(),
                group.validTo()
            ))
            .toList();
        String summaryReason = blockers.isEmpty() ? null : blockers.get(0).reason();
        return new EffectiveSupplierEligibility(
            supplierId, effectiveDate, overallStatus, eligibleForRequestedScope, groups, blockers, summaryReason
        );
    }

    /** Union of every material group a supplier has ever requested or been granted, used as the
     * evaluation scope when the caller does not pin the eligibility check to specific groups. */
    private List<UUID> knownMaterialGroupScopes(UUID supplierId) {
        List<SupplierAccreditationApplication> applications = repository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId);
        java.util.LinkedHashSet<UUID> groupIds = new java.util.LinkedHashSet<>();
        for (SupplierAccreditationApplication application : applications) {
            if (application.getRequestedMaterialGroupIds() != null) {
                groupIds.addAll(application.getRequestedMaterialGroupIds());
            }
            decisionRepository.findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()
            ).forEach(decision -> {
                if (decision.getAllowedMaterialGroupIds() != null) {
                    groupIds.addAll(decision.getAllowedMaterialGroupIds());
                }
            });
        }
        groupIds.remove(null);
        return List.copyOf(groupIds);
    }

    private Map<UUID, String> materialGroupCodes(List<MaterialGroupEligibility> groups) {
        List<UUID> ids = groups.stream()
            .map(MaterialGroupEligibility::materialGroupId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return materialGroupRepository
            .findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), ids)
            .stream()
            .collect(java.util.stream.Collectors.toMap(
                com.enterprise.mm.entity.MaterialGroup::getId, com.enterprise.mm.entity.MaterialGroup::getCode));
    }

    private String documentTypeFor(MaterialGroupEligibility group) {
        if (group.decisionId() != null) {
            return "ACCREDITATION_DECISION";
        }
        return group.applicationId() != null ? "ACCREDITATION_APPLICATION" : null;
    }

    private UUID documentIdFor(MaterialGroupEligibility group) {
        return group.decisionId() != null ? group.decisionId() : group.applicationId();
    }

    private String documentCodeFor(MaterialGroupEligibility group) {
        if (group.decisionId() != null) {
            return decisionRepository.findByIdAndIsDeletedFalse(group.decisionId())
                .map(SupplierAccreditationDecision::getCode)
                .or(() -> group.applicationId() == null
                    ? java.util.Optional.empty()
                    : repository.findByIdAndIsDeletedFalse(group.applicationId())
                        .map(SupplierAccreditationApplication::getCode))
                .orElse(null);
        }
        if (group.applicationId() == null) {
            return null;
        }
        return repository.findByIdAndIsDeletedFalse(group.applicationId())
            .map(SupplierAccreditationApplication::getCode)
            .orElse(null);
    }

    private MaterialGroupEligibility materialGroupEligibility(
        UUID materialGroupId,
        List<SupplierAccreditationApplication> applications,
        LocalDate today,
        String source
    ) {
        boolean governanceScope = isSupplierGovernanceSource(source);
        for (SupplierAccreditationApplication application : applications) {
            if (!isMaterialAccreditation(application) && !governanceScope) {
                continue;
            }
            SupplierAccreditationDecision decision = effectiveDecision(application, today);
            if (decision != null && decision.getAllowedMaterialGroupIds().contains(materialGroupId)) {
                return new MaterialGroupEligibility(
                    materialGroupId,
                    true,
                    null,
                    null,
                    application.getId(),
                    application.getStatus(),
                    decision.getId(),
                    decision.getValidTo(),
                    decision.getConditions()
                );
            }
        }
        MaterialGroupEligibility diagnosis = diagnoseMaterialGroupIneligibility(materialGroupId, applications, today, source);
        if (diagnosis != null) {
            return diagnosis;
        }
        return new MaterialGroupEligibility(
            materialGroupId,
            false,
            REASON_SUPPLIER_MATERIAL_GROUP_NOT_COVERED,
            "Supplier accreditation does not cover the material group for " + source,
            null,
            null,
            null,
            null,
            null
        );
    }

    /**
     * UX-LIVE-02: {@link #effectiveDecisionBase} collapses "expired decision" and "missing
     * evidence" to the same {@code null}, which is correct for the eligibility gate but not
     * informative enough for the UI. This walks the same applications a second time, ignoring
     * evidence and decision-date currency one at a time, to find the specific reason a decision
     * that would otherwise cover the material group is not currently usable.
     */
    private MaterialGroupEligibility diagnoseMaterialGroupIneligibility(
        UUID materialGroupId,
        List<SupplierAccreditationApplication> applications,
        LocalDate today,
        String source
    ) {
        boolean governanceScope = isSupplierGovernanceSource(source);
        for (SupplierAccreditationApplication application : applications) {
            if (!isMaterialAccreditation(application) && !governanceScope) {
                continue;
            }
            SupplierAccreditationDecision decision = coveringDecisionIgnoringEvidence(application, materialGroupId);
            if (decision == null) {
                continue;
            }
            if (!DECISION_STATUS_RECORDED.equals(decision.getStatus())) {
                return new MaterialGroupEligibility(
                    materialGroupId,
                    false,
                    REASON_SUPPLIER_ACCREDITATION_DECISION_PENDING,
                    "Supplier accreditation decision is pending approval for " + source,
                    application.getId(),
                    application.getStatus(),
                    decision.getId(),
                    decision.getValidTo(),
                    decision.getConditions()
                );
            }
            if (decision.getValidTo() != null && decision.getValidTo().isBefore(today)) {
                return new MaterialGroupEligibility(
                    materialGroupId,
                    false,
                    REASON_SUPPLIER_ACCREDITATION_EXPIRED,
                    "Supplier accreditation for the material group expired on " + decision.getValidTo() + " for " + source,
                    application.getId(),
                    application.getStatus(),
                    decision.getId(),
                    decision.getValidTo(),
                    decision.getConditions()
                );
            }
            if (!isCurrent(decision.getValidFrom(), decision.getValidTo(), today)) {
                continue;
            }
            if (!hasCurrentVerifiedEvidence(application, today)) {
                return new MaterialGroupEligibility(
                    materialGroupId,
                    false,
                    REASON_SUPPLIER_EVIDENCE_MISSING,
                    "Supplier accreditation evidence is missing or unverified for the material group for " + source,
                    application.getId(),
                    application.getStatus(),
                    null,
                    null,
                    decision.getConditions()
                );
            }
        }
        return null;
    }

    /** Same application/decision match as {@link #effectiveDecisionBase} minus the decision-date
     * currency and evidence checks, so callers can diagnose exactly which of those failed. */
    private SupplierAccreditationDecision coveringDecisionIgnoringEvidence(
        SupplierAccreditationApplication application,
        UUID materialGroupId
    ) {
        SupplierAccreditationDecision decision = decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), application.getCompanyId(), application.getId())
            .stream()
            .findFirst()
            .orElse(null);
        if (decision == null
            || !application.getStatus().equals(decision.getDecision())
            || !ELIGIBLE_STATUSES.contains(decision.getDecision())
            || decision.getAllowedMaterialGroupIds() == null
            || !decision.getAllowedMaterialGroupIds().contains(materialGroupId)) {
            return null;
        }
        return decision;
    }

    private boolean hasCurrentVerifiedEvidence(SupplierAccreditationApplication application, LocalDate today) {
        if (application == null || application.getId() == null) {
            return false;
        }
        boolean directVerified = documentRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), application.getCompanyId(), application.getId())
            .stream()
            .anyMatch(document -> evidencePolicy.isCurrentVerifiedEvidence(document, today));
        if (directVerified) {
            return true;
        }
        if (checklistService == null) {
            return false;
        }
        return checklistService.checklist(application, today).stream()
            .anyMatch(item -> item.state() == com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.EvidenceChecklistItemState.VERIFIED);
    }

    /** UX-LIVE-02: distinguishes a manual blacklist entry from a generic compliance block so the
     * blocker code points the user at the right remediation flow. */
    private boolean isBlacklistReason(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.toLowerCase(Locale.ROOT).contains("blacklist") || reason.contains("BLOCKED");
    }

    protected SupplierAccreditationApplication findEntity(UUID id) {
        SupplierAccreditationApplication entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateTenantCompanyScope(entity);
        return entity;
    }

    private SupplierAccreditationApplication findMutableEntity(UUID id) {
        SupplierAccreditationApplication entity = findEntity(id);
        requireCurrentCompany(entity);
        return entity;
    }

    private void beforeCreate(SupplierAccreditationApplication entity) {
        Supplier supplier = complianceService.validateSupplier(entity.getSupplierId());
        if (supplier == null) {
            throw new BusinessException("Supplier not found");
        }
        if (!Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())) {
            throw new BusinessException("Supplier must belong to the current company");
        }
        entity.setRequestedMaterialGroupIds(normalizeMaterialGroupIds(entity.getRequestedMaterialGroupIds()));
        requireRequestedMaterialGroupScope(entity);
        entity.setStatus(SupplierAccreditationStatus.DRAFT.name());
        entity.setSubmittedAt(null);
        entity.setReviewStartedAt(null);
        entity.setRiskScore(BigDecimal.ZERO);
        entity.setReviewComment(null);
        entity.setComplianceRecommendation(null);
        entity.setComplianceSnapshotJson(null);
        entity.setCompanyId(supplier.getCompanyId());
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName("Supplier accreditation " + entity.getAccreditationType());
        }
        entity.setRiskScore(BigDecimal.ZERO);
    }

    private void beforeUpdate(SupplierAccreditationApplication entity) {
        Supplier supplier = complianceService.validateSupplier(entity.getSupplierId());
        if (supplier == null) {
            throw new BusinessException("Supplier not found");
        }
        if (!Objects.equals(CompanyContext.currentCompany(), supplier.getCompanyId())) {
            throw new BusinessException("Supplier must belong to the current company");
        }
        entity.setRequestedMaterialGroupIds(normalizeMaterialGroupIds(entity.getRequestedMaterialGroupIds()));
        requireRequestedMaterialGroupScope(entity);
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        if (entity.getRiskScore() == null) {
            entity.setRiskScore(BigDecimal.ZERO);
        }
    }

    private void validateTenantCompanyScope(SupplierAccreditationApplication entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }

    private void ensureEditable(SupplierAccreditationApplication entity) {
        if (!SupplierAccreditationStatus.DRAFT.name().equals(entity.getStatus())
            && !SupplierAccreditationStatus.NEEDS_CLARIFICATION.name().equals(entity.getStatus())) {
            throw new BusinessException("Accreditation application is editable only in DRAFT or NEEDS_CLARIFICATION status");
        }
    }

    private boolean companyScoped() {
        return true;
    }

    private void ensureStatus(SupplierAccreditationApplication entity, SupplierAccreditationStatus... allowed) {
        List<String> allowedStatuses = java.util.Arrays.stream(allowed).map(Enum::name).toList();
        String currentStatus = effectiveStatus(entity);
        if (!allowedStatuses.contains(currentStatus)) {
            throw new BusinessException("Unsupported accreditation status transition from " + currentStatus);
        }
    }

    private void validateApprovalReadiness(
        SupplierAccreditationApplication entity,
        SupplierAccreditationDecisionActionRequest request
    ) {
        if (request == null) {
            throw new BusinessException("Accreditation decision request is required");
        }
        LocalDate validFrom = approvalValidFrom(entity, request);
        LocalDate validTo = approvalValidTo(entity, request);
        if (validFrom == null || validTo == null) {
            throw new BusinessException("Accreditation validity dates are required");
        }
        if (validTo.isBefore(validFrom) || validTo.isBefore(LocalDate.now())) {
            throw new BusinessException("Accreditation validity period must be current or future and validTo must not precede validFrom");
        }
        String type = entity.getAccreditationType() == null
            ? ""
            : entity.getAccreditationType().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        List<UUID> groups = approvedMaterialGroupIds(entity, request);
        boolean requestedScopeBlank = entity.getRequestedScope() == null || entity.getRequestedScope().isBlank();
        if (MATERIAL_ACCREDITATION_TYPES.contains(type) && groups.isEmpty()) {
            throw new BusinessException("At least one allowed material group is required for goods accreditation");
        }
        if (MATERIAL_ACCREDITATION_TYPES.contains(type)) {
            validateMaterialGroupScope(entity, groups);
        }
        if ((SERVICE_ACCREDITATION_TYPES.contains(type) || WORKS_ACCREDITATION_TYPES.contains(type))
            && requestedScopeBlank) {
            throw new BusinessException("Requested scope is required for services or works accreditation");
        }
        if (!MATERIAL_ACCREDITATION_TYPES.contains(type)
            && !SERVICE_ACCREDITATION_TYPES.contains(type)
            && !WORKS_ACCREDITATION_TYPES.contains(type)
            && groups.isEmpty()
            && requestedScopeBlank) {
            throw new BusinessException("Requested accreditation scope is required");
        }
        if (!groups.isEmpty() && !MATERIAL_ACCREDITATION_TYPES.contains(type)) {
            validateMaterialGroupScope(entity, groups);
        }
        LocalDate today = LocalDate.now();
        if (!hasCurrentVerifiedEvidence(entity, today)) {
            throw new BusinessException("At least one current VERIFIED accreditation document with an active linked attachment is required");
        }
    }

    private BigDecimal calculateRiskScore(SupplierComplianceSummary summary) {
        BigDecimal base = switch (summary.recommendedStatus()) {
            case "BLOCKED" -> BigDecimal.valueOf(90);
            case "LIMITED" -> BigDecimal.valueOf(55);
            default -> BigDecimal.TEN;
        };
        return base
            .add(BigDecimal.valueOf(summary.failedKycChecks() * 5L))
            .add(BigDecimal.valueOf(summary.highRiskRelatedParties() * 7L))
            .add(BigDecimal.valueOf(summary.openCorrectiveActions() * 3L));
    }

    private String normalizeApprovalStatus(String decision, String complianceRecommendation) {
        String normalized = decision == null || decision.isBlank()
            ? null
            : decision.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (SupplierStatus.LIMITED.name().equals(complianceRecommendation)) {
            return SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name();
        }
        if (normalized == null) {
            return SupplierAccreditationStatus.APPROVED.name();
        }
        return switch (normalized) {
            case "APPROVED" -> SupplierAccreditationStatus.APPROVED.name();
            case "CONDITIONALLY_APPROVED", "CONDITIONAL", "CONDITIONALLY" -> SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name();
            default -> throw new BusinessException("Unsupported accreditation approval decision: " + decision);
        };
    }

    private void saveDecision(SupplierAccreditationApplication entity, SupplierAccreditationDecisionActionRequest request, String finalStatus) {
        SupplierAccreditationDecision decision = new SupplierAccreditationDecision();
        decision.setApplicationId(entity.getId());
        decision.setCompanyId(entity.getCompanyId());
        decision.setCode("ACC-DEC-" + UUID.randomUUID().toString().substring(0, 8));
        decision.setName(BusinessNameGenerator.workflowDocumentName("SupplierAccreditationDecision", entity.getName()));
        decision.setStatus("RECORDED");
        decision.setDecision(finalStatus);
        decision.setApprovedScope(entity.getRequestedScope());
        decision.setAllowedMaterialGroupIds(approvedMaterialGroupIds(entity, request));
        decision.setValidFrom(entity.getValidFrom());
        decision.setValidTo(entity.getValidTo());
        decision.setConditions(null);
        decision.setCommissionProtocolNo(null);
        decisionRepository.save(decision);
    }

    private SupplierAccreditationDecision effectiveDecision(
        SupplierAccreditationApplication application,
        LocalDate today
    ) {
        SupplierAccreditationDecision decision = effectiveDecisionBase(application, today);
        if (decision == null
            || decision.getAllowedMaterialGroupIds() == null
            || decision.getAllowedMaterialGroupIds().isEmpty()) {
            return null;
        }
        return decision;
    }

    private SupplierAccreditationDecision effectiveDecisionBase(
        SupplierAccreditationApplication application,
        LocalDate today
    ) {
        if (!isCurrent(application.getValidFrom(), application.getValidTo(), today)) {
            return null;
        }
        SupplierAccreditationDecision decision = decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), application.getCompanyId(), application.getId())
            .stream()
            .findFirst()
            .orElse(null);
        if (decision == null
            || !"RECORDED".equals(decision.getStatus())
            || !application.getStatus().equals(decision.getDecision())
            || !ELIGIBLE_STATUSES.contains(decision.getDecision())
            || !isCurrent(decision.getValidFrom(), decision.getValidTo(), today)) {
            return null;
        }
        if (!hasCurrentVerifiedEvidence(application, today)) {
            return null;
        }
        return decision;
    }

    private boolean isCurrent(LocalDate validFrom, LocalDate validTo, LocalDate today) {
        return validFrom != null
            && validTo != null
            && !validFrom.isAfter(today)
            && !validTo.isBefore(today);
    }

    private List<UUID> normalizeMaterialGroupIds(Collection<UUID> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().toList();
    }

    private void requireRequestedMaterialGroupScope(SupplierAccreditationApplication entity) {
        if (isMaterialAccreditation(entity) && normalizeMaterialGroupIds(entity.getRequestedMaterialGroupIds()).isEmpty()) {
            throw new BusinessException("Requested material groups are required for goods accreditation");
        }
    }

    private List<UUID> approvedMaterialGroupIds(
        SupplierAccreditationApplication entity,
        SupplierAccreditationDecisionActionRequest request
    ) {
        if (request != null && request.allowedMaterialGroupIds() != null) {
            return normalizeMaterialGroupIds(request.allowedMaterialGroupIds());
        }
        return isMaterialAccreditation(entity)
            ? normalizeMaterialGroupIds(entity.getRequestedMaterialGroupIds())
            : List.of();
    }

    private LocalDate approvalValidFrom(
        SupplierAccreditationApplication entity,
        SupplierAccreditationDecisionActionRequest request
    ) {
        return request != null && request.validFrom() != null ? request.validFrom() : entity.getValidFrom();
    }

    private LocalDate approvalValidTo(
        SupplierAccreditationApplication entity,
        SupplierAccreditationDecisionActionRequest request
    ) {
        return request != null && request.validTo() != null ? request.validTo() : entity.getValidTo();
    }

    private String normalizeToken(String value) {
        return value == null
            ? ""
            : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private boolean isMaterialAccreditation(SupplierAccreditationApplication application) {
        return MATERIAL_ACCREDITATION_TYPES.contains(normalizeToken(application.getAccreditationType()));
    }

    private boolean isSupplierGovernanceSource(String source) {
        return source != null && source.toLowerCase(Locale.ROOT).contains("supplier governance");
    }

    private boolean hasTypedScope(
        List<EffectiveAccreditation> effectiveAccreditations,
        Set<String> acceptedTypes
    ) {
        return effectiveAccreditations.stream().anyMatch(effective ->
            acceptedTypes.contains(normalizeToken(effective.application().getAccreditationType()))
                && effective.decision().getApprovedScope() != null
                && !effective.decision().getApprovedScope().isBlank());
    }

    private record EffectiveAccreditation(
        SupplierAccreditationApplication application,
        SupplierAccreditationDecision decision
    ) {
    }

    private String effectiveStatus(SupplierAccreditationApplication application) {
        return ELIGIBLE_STATUSES.contains(application.getStatus())
            && application.getValidTo() != null
            && application.getValidTo().isBefore(LocalDate.now())
            ? SupplierAccreditationStatus.EXPIRED.name()
            : application.getStatus();
    }

    private SupplierAccreditationApplicationResponse toResponse(SupplierAccreditationApplication application) {
        SupplierAccreditationApplicationResponse response = mapper.toResponse(application);
        String effectiveStatus = effectiveStatus(application);
        response.setStatus(effectiveStatus);
        enrichWithApprovalState(response, application, effectiveStatus);
        return response;
    }

    /** Task 10: allowedActions / approvalInstanceId / approvalStatus. */
    private void enrichWithApprovalState(
        SupplierAccreditationApplicationResponse response,
        SupplierAccreditationApplication application,
        String effectiveStatus
    ) {
        ApprovalInstance instance = approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                application.getTenantId(), application.getCompanyId(), APPROVAL_MODULE_KEY, application.getId())
            .orElse(null);
        response.setApprovalInstanceId(instance == null ? null : instance.getId());
        response.setApprovalStatus(instance == null ? null : instance.getApprovalStatus().name());

        List<String> actions = new ArrayList<>();
        boolean onApproval = instance != null
            && (instance.getApprovalStatus() == ApprovalInstanceStatus.DRAFT
                || instance.getApprovalStatus() == ApprovalInstanceStatus.PENDING);
        switch (effectiveStatus) {
            case "SUBMITTED", "NEEDS_CLARIFICATION" -> actions.add("START_REVIEW");
            case "UNDER_REVIEW" -> actions.add("RUN_COMPLIANCE_CHECK");
            default -> {
            }
        }
        if ("SUBMITTED".equals(effectiveStatus) || "UNDER_REVIEW".equals(effectiveStatus) || "COMPLIANCE_CHECK".equals(effectiveStatus)) {
            actions.add("REQUEST_CLARIFICATION");
        }
        if ("COMPLIANCE_CHECK".equals(effectiveStatus) || "COMMITTEE_REVIEW".equals(effectiveStatus)) {
            if (!onApproval) {
                actions.add("SUBMIT_FOR_APPROVAL");
            }
        }
        boolean earlyRejectEligible = List.of(
            "SUBMITTED", "UNDER_REVIEW", "COMPLIANCE_CHECK", "COMMITTEE_REVIEW", "NEEDS_CLARIFICATION"
        ).contains(effectiveStatus);
        if (earlyRejectEligible && !onApproval) {
            actions.add("REJECT");
        }
        if ("APPROVED".equals(effectiveStatus) || "CONDITIONALLY_APPROVED".equals(effectiveStatus)) {
            actions.add("SUSPEND");
        }
        if (List.of("APPROVED", "CONDITIONALLY_APPROVED", "EXPIRED", "SUSPENDED").contains(effectiveStatus)) {
            actions.add("RENEW");
        }
        response.setAllowedActions(actions);
    }

    private void requireCurrentCompany(SupplierAccreditationApplication application) {
        if (!Objects.equals(CompanyContext.currentCompany(), application.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + application.getId());
        }
    }

    private void validateMaterialGroupScope(
        SupplierAccreditationApplication application,
        List<UUID> allowedGroups
    ) {
        Set<UUID> requestedGroups = Set.copyOf(normalizeMaterialGroupIds(application.getRequestedMaterialGroupIds()));
        if (!requestedGroups.containsAll(allowedGroups)) {
            throw new BusinessException("Allowed material groups must be within the requested scope");
        }
        Set<UUID> existingGroups = materialGroupRepository
            .findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
                application.getTenantId(), application.getCompanyId(), allowedGroups)
            .stream()
            .map(com.enterprise.mm.entity.MaterialGroup::getId)
            .collect(java.util.stream.Collectors.toSet());
        if (!existingGroups.equals(Set.copyOf(allowedGroups))) {
            throw new BusinessException("Allowed scope must contain only active material groups from the accreditation company");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize compliance snapshot");
        }
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String searchPattern(SearchCriteria request) {
        String search = request.normalizedSearch();
        return search.isBlank() ? null : "%" + search.toLowerCase(Locale.ROOT) + "%";
    }

    private Specification<SupplierAccreditationApplication> applicationSearchSpec(
        SupplierAccreditationSearchRequest criteria,
        boolean scoped
    ) {
        SearchCriteria filter = SearchCriteria.of(criteria);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), TenantContext.currentTenant()));
            if (scoped) {
                predicates.add(cb.equal(root.get("companyId"), CompanyContext.currentCompany()));
            }
            if (!filter.includeDeleted()) {
                predicates.add(cb.isFalse(root.get("isDeleted")));
            }
            String searchPattern = searchPattern(filter);
            if (searchPattern != null) {
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("code").as(String.class)), searchPattern),
                    cb.like(cb.lower(root.get("name").as(String.class)), searchPattern)
                ));
            }
            LocalDateTime dateFrom = filter.fromDateTime();
            if (dateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
            }
            LocalDateTime dateTo = filter.toDateTime();
            if (dateTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), dateTo));
            }
            List<String> statuses = filter.hasStatus()
                ? filter.status().stream().filter(Objects::nonNull).toList()
                : List.of();
            if (!statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (filter.supplierId() != null) {
                predicates.add(cb.equal(root.get("supplierId"), filter.supplierId()));
            }
            String accreditationType = normalizeFilter(criteria.accreditationType());
            if (accreditationType != null) {
                predicates.add(cb.equal(cb.lower(root.get("accreditationType").as(String.class)), accreditationType));
            }
            String complianceRecommendation = normalizeFilter(criteria.complianceRecommendation());
            if (complianceRecommendation != null) {
                predicates.add(cb.equal(cb.lower(root.get("complianceRecommendation").as(String.class)), complianceRecommendation));
            }
            if (criteria.submittedFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("submittedAt"), criteria.submittedFrom().atStartOfDay()));
            }
            if (criteria.submittedTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("submittedAt"), criteria.submittedTo().atTime(java.time.LocalTime.MAX)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}

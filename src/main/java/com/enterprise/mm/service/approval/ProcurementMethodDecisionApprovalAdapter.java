package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.enums.ProcurementMethodDecisionStatus;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.IntegrationEventService;
import com.enterprise.mm.service.ProcurementMethodCapabilityService;
import com.enterprise.mm.service.ProcurementMethodPolicy;
import com.enterprise.mm.service.ProcurementProcedureService;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcurementMethodDecisionApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "PROCUREMENT_METHOD_DECISION";

    private final ProcurementMethodDecisionRepository repository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final AttachmentRepository attachmentRepository;
    private final ProcurementMethodPolicy methodPolicy;
    private final ProcurementProcedureService procedureService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;
    private final ProcurementMethodCapabilityService capabilityService;



    @Override
    public String moduleKey() {
        return MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return find(entityId).getCompanyId();
    }

    @Override
    public String displayName(UUID entityId) {
        ProcurementMethodDecision decision = find(entityId);
        return decision.getName();
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        ProcurementMethodDecision decision = find(entityId);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("decisionNumber", decision.getDecisionNumber());
        values.put("selectedMethod", decision.getSelectedMethod());
        values.put("estimatedAmount", amount(decision.getEstimatedAmount()));
        values.put("currencyCode", currency(decision.getCurrencyCode()));
        values.put("potentialSupplierCount", decision.getPotentialSupplierCount());
        values.put("purchaseRequestId", decision.getPurchaseRequestId());
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        return List.of(new ApprovalDetailItem(
            decision.getId() == null ? null : decision.getId().toString(),
            decision.getDecisionNumber() == null ? decision.getName() : decision.getDecisionNumber(),
            values
        ));
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        ProcurementMethodDecision decision = find(entityId);
        if (!ProcurementMethodDecisionStatus.DRAFT.name().equals(decision.getStatus())
            && !ProcurementMethodDecisionStatus.RETURNED.name().equals(decision.getStatus())) {
            throw new BusinessException("Procurement method decision must be DRAFT or RETURNED, current status is " + decision.getStatus());
        }
        if (amount(decision.getEstimatedAmount()).signum() <= 0) {
            throw new BusinessException("estimatedAmount must be greater than zero");
        }
        requireCapability(decision);
        if (currency(decision.getCurrencyCode()).length() != 3) {
            throw new BusinessException("currencyCode is required and must contain 3 characters");
        }
        requireAttachment(decision.getMarketResearchAttachmentId(), "marketResearchAttachmentId");
        requireAttachment(decision.getTechnicalSpecificationAttachmentId(), "technicalSpecificationAttachmentId");
        ProcurementMethodType method = methodPolicy.require(decision.getSelectedMethod());
        if (requiresSupplierDepth(method) && (decision.getPotentialSupplierCount() == null || decision.getPotentialSupplierCount() < 2)) {
            throw new BusinessException("Competitive procurement requires at least two potential suppliers");
        }
        if (method == ProcurementMethodType.SINGLE_SOURCE
            && (decision.getPotentialSupplierCount() == null || decision.getPotentialSupplierCount() != 1)) {
            throw new BusinessException("Single source procurement requires exactly one potential supplier");
        }
        if (method.requiresExceptionReason()) {
            String justification = decision.getJustification() == null ? "" : decision.getJustification().trim();
            if (justification.length() < 10) {
                throw new BusinessException("justification must contain a meaningful explanation");
            }
            if (decision.getExceptionReason() == null || decision.getExceptionReason().isBlank()) {
                throw new BusinessException("exceptionReason is required for " + method.name());
            }
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        ProcurementMethodDecision decision = find(entityId);
        if (ProcurementMethodDecisionStatus.UNDER_REVIEW.name().equals(decision.getStatus())) {
            return;
        }
        decision.setStatus(ProcurementMethodDecisionStatus.UNDER_REVIEW.name());
        decision.setSubmittedAt(LocalDateTime.now());
        ProcurementMethodDecision saved = repository.save(decision);
        integrationEventService.emit("PROCUREMENT.METHOD_DECISION_SUBMITTED", "ProcurementMethodDecision", entityId, decisionEventPayload(entityId, saved));
        auditService.log("ProcurementMethodDecision", entityId, "SUBMIT", saved.getDecisionNumber(), null, null);
    }

    @Override
    public void beforeApproveTask(UUID entityId, ApprovalFlowStep step, ApprovalTaskDecisionRequest request) {
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("ProcurementMethodDecision", find(entityId), request == null ? null : request.comment());
        }
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        ProcurementMethodDecision decision = find(entityId);
        if (ProcurementMethodDecisionStatus.APPROVED.name().equals(decision.getStatus())) {
            return;
        }
        if (!ProcurementMethodDecisionStatus.UNDER_REVIEW.name().equals(decision.getStatus())) {
            throw new BusinessException("Procurement method decision must be UNDER_REVIEW, current status is " + decision.getStatus());
        }
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("ProcurementMethodDecision", decision, comment);
        }
        var capability = requireCapability(decision);
        decision.setStatus(ProcurementMethodDecisionStatus.APPROVED.name());
        decision.setApprovedAt(LocalDateTime.now());
        decision.setApprovedBy(currentActor());
        if (capability.state() != null) {
            decision.setCapabilityStateSnapshot(capability.state().name());
        }
        if (capability.metadata() != null) {
            decision.setCapabilityVersionSnapshot(capability.metadata().configurationVersion());
        }
        ProcurementMethodDecision saved = repository.save(decision);
        procedureService.createForApprovedDecision(saved);
        integrationEventService.emit("PROCUREMENT.METHOD_DECISION_APPROVED", "ProcurementMethodDecision", entityId, decisionEventPayload(entityId, saved));
        auditService.log("ProcurementMethodDecision", entityId, "APPROVE", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        ProcurementMethodDecision decision = find(entityId);
        if (!ProcurementMethodDecisionStatus.UNDER_REVIEW.name().equals(decision.getStatus())) {
            throw new BusinessException("Procurement method decision must be UNDER_REVIEW, current status is " + decision.getStatus());
        }
        if (comment == null || comment.isBlank()) {
            throw new BusinessException("rejectionReason is required");
        }
        decision.setStatus(ProcurementMethodDecisionStatus.REJECTED.name());
        decision.setRejectedAt(LocalDateTime.now());
        decision.setRejectedBy(currentActor());
        decision.setRejectionReason(comment.trim());
        repository.save(decision);
        integrationEventService.emit("PROCUREMENT.METHOD_DECISION_REJECTED", "ProcurementMethodDecision", entityId, Map.of(
            "decisionId", entityId,
            "rejectionReason", comment.trim()
        ));
        auditService.log("ProcurementMethodDecision", entityId, "REJECT", comment, null, null);
    }

    @Override
    public void onReturned(UUID entityId, String comment) {
        ProcurementMethodDecision decision = find(entityId);
        if (ProcurementMethodDecisionStatus.RETURNED.name().equals(decision.getStatus())) {
            return;
        }
        if (!ProcurementMethodDecisionStatus.UNDER_REVIEW.name().equals(decision.getStatus())) {
            throw new BusinessException("Procurement method decision cannot be returned from status " + decision.getStatus());
        }
        decision.setStatus(ProcurementMethodDecisionStatus.RETURNED.name());
        repository.save(decision);
        auditService.log("ProcurementMethodDecision", entityId, "RETURN", comment, null, null);
    }

    private ProcurementMethodDecision find(UUID id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .filter(decision -> TenantContext.currentTenant().equals(decision.getTenantId())
                && com.enterprise.mm.security.AccessScope.canAccessCompany(decision.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ProcurementMethodDecision not found: " + id));
    }

    private Map<String, Object> decisionEventPayload(UUID entityId, ProcurementMethodDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decisionId", entityId);
        payload.put("selectedMethod", decision.getSelectedMethod());
        return payload;
    }

    private Attachment requireAttachment(UUID id, String field) {
        if (id == null) {
            throw new BusinessException(field + " is required");
        }
        return attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), id
        ).orElseThrow(() -> new BusinessException(field + " not found: " + id));
    }

    private boolean requiresSupplierDepth(ProcurementMethodType method) {
        return method.isCompetitive()
            && method != ProcurementMethodType.OPEN_TENDER
            && method != ProcurementMethodType.OPEN_COMPETITION;
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String requestType(ProcurementMethodDecision decision) {
        if (decision.getPurchaseRequestId() == null) return null;
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(decision.getPurchaseRequestId())
            .map(request -> request.getRequestType() == null ? null : request.getRequestType().name())
            .orElse(null);
    }

    private com.enterprise.mm.payload.ProcurementMethodCapabilityDtos.Evaluation requireCapability(ProcurementMethodDecision decision) {
        var categories = categories(decision);
        if (categories.isEmpty()) return capabilityService.requireAvailableForCompany(decision.getSelectedMethod(), decision.getEstimatedAmount(), decision.getCurrencyCode(), requestType(decision), null, decision.getCompanyId());
        com.enterprise.mm.payload.ProcurementMethodCapabilityDtos.Evaluation result = null;
        for (String category : categories) result = capabilityService.requireAvailableForCompany(decision.getSelectedMethod(), decision.getEstimatedAmount(), decision.getCurrencyCode(), requestType(decision), category, decision.getCompanyId());
        return result;
    }

    private java.util.Set<String> categories(ProcurementMethodDecision decision) {
        if (decision.getPurchaseRequestId() == null) return java.util.Set.of();
        return purchaseRequestItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(TenantContext.currentTenant(), decision.getCompanyId(), java.util.List.of(decision.getPurchaseRequestId()))
            .stream().map(item -> item.getMaterial() == null ? item.getServiceCategory() : item.getMaterial().getMaterialGroupId() == null ? item.getServiceCategory() : item.getMaterial().getMaterialGroupId().toString())
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private String currency(String value) {
        return value == null || value.isBlank() ? "UZS" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }
}

package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementDirectAward;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.repository.ProcurementDirectAwardRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ProcurementMethodPolicy;
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
public class ProcurementDirectAwardApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "PROCUREMENT_DIRECT_AWARD";
    public static final String SINGLE_SOURCE_MODULE_KEY = "PROCUREMENT_SINGLE_SOURCE_AWARD";

    private final ProcurementDirectAwardRepository repository;
    private final ProcurementProcedureRepository procedureRepository;
    private final ProcurementMethodPolicy methodPolicy;
    private final AuditService auditService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;

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
        ProcurementDirectAward award = find(entityId);
        return award.getName() == null || award.getName().isBlank() ? award.getCode() : award.getName();
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        ProcurementDirectAward award = find(entityId);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("awardCode", award.getCode());
        values.put("supplierId", award.getSupplierId());
        values.put("method", award.getMethod());
        values.put("awardAmount", award.getAwardAmount());
        values.put("currencyCode", award.getCurrencyCode());
        values.put("awardBasisType", award.getAwardBasisType());
        values.put("awardBasis", award.getAwardBasis());
        values.put("justification", award.getJustification());
        values.put("purchaseRequestId", award.getPurchaseRequestId());
        values.put("procurementProcedureId", award.getProcurementProcedureId());
        values.put("evidenceReference", award.getEvidenceReference());
        values.put("marketPriceAmount", award.getMarketPriceAmount());
        values.put("marketPriceEvidenceReference", award.getMarketPriceEvidenceReference());
        values.put("negotiationSummary", award.getNegotiationSummary());
        values.put("conflictOfInterestDeclaration", award.getConflictOfInterestDeclaration());
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        return List.of(new ApprovalDetailItem(
            award.getId() == null ? null : award.getId().toString(),
            displayName(entityId),
            values
        ));
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        ProcurementDirectAward award = find(entityId);
        if (!"DRAFT".equals(award.getStatus()) && !"RETURNED".equals(award.getStatus())) {
            throw new BusinessException("Direct award must be DRAFT or RETURNED, current status is " + award.getStatus());
        }
        ProcurementProcedure procedure = requireDirectAwardProcedure(award.getProcurementProcedureId());
        if (!ProcurementProcedureStatus.DIRECT_AWARD_DRAFT.name().equals(procedure.getStatus())) {
            throw new BusinessException("Procurement procedure must be DIRECT_AWARD_DRAFT before direct award submission");
        }
        ProcurementMethodType method = methodPolicy.require(procedure.getMethod());
        if (award.getMethod() == null) {
            award.setMethod(method.name());
        }
        if (!method.name().equals(award.getMethod())) {
            throw new BusinessException("Direct award method differs from its procurement procedure");
        }
        if (award.getSupplierId() == null) {
            throw new BusinessException("supplierId is required for direct award approval");
        }
        if (award.getAwardAmount() == null || award.getAwardAmount().signum() <= 0) {
            throw new BusinessException("awardAmount must be greater than zero");
        }
        if (award.getCurrencyCode() == null || award.getCurrencyCode().isBlank() || award.getCurrencyCode().trim().length() != 3) {
            throw new BusinessException("currencyCode is required and must contain 3 characters");
        }
        if (award.getJustification() == null || award.getJustification().trim().length() < 10) {
            throw new BusinessException("justification must contain a meaningful explanation");
        }
        if (award.getAwardBasisType() == null || award.getAwardBasisType().isBlank()) {
            throw new BusinessException("awardBasisType is required");
        }
        requireGovernanceEvidence(award, method);
    }

    @Override
    public void markSubmitted(UUID entityId) {
        ProcurementDirectAward award = find(entityId);
        if ("APPROVAL_PENDING".equals(award.getStatus())) {
            return;
        }
        award.setStatus("APPROVAL_PENDING");
        ProcurementDirectAward saved = repository.save(award);
        auditService.log("ProcurementDirectAward", entityId, "SUBMIT_FOR_APPROVAL", saved.getCode(), null, null);
    }

    @Override
    public void beforeApproveTask(UUID entityId, ApprovalFlowStep step, ApprovalTaskDecisionRequest request) {
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("ProcurementDirectAward", find(entityId), request == null ? null : request.comment());
        }
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        ProcurementDirectAward award = find(entityId);
        if ("APPROVED".equals(award.getStatus())) {
            return;
        }
        if (!"APPROVAL_PENDING".equals(award.getStatus())) {
            throw new BusinessException("Direct award must be APPROVAL_PENDING, current status is " + award.getStatus());
        }
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("ProcurementDirectAward", award, comment);
        }
        ProcurementProcedure procedure = requireDirectAwardProcedure(award.getProcurementProcedureId());
        ProcurementMethodType method = methodPolicy.require(procedure.getMethod());
        if (!method.name().equals(award.getMethod())) {
            throw new BusinessException("Direct award method changed during approval; restart approval");
        }
        award.setStatus("APPROVED");
        award.setApprovedAt(LocalDateTime.now());
        award.setApprovedBy(currentActor());
        award.setApprovedSupplierId(award.getSupplierId());
        award.setApprovedAwardAmount(award.getAwardAmount());
        award.setApprovedMethod(award.getMethod());
        ProcurementDirectAward saved = repository.save(award);
        procedure.setDirectAwardId(saved.getId());
        procedure.setFinalizedAmount(nvl(saved.getAwardAmount()));
        procedure.setAwardedSupplierId(saved.getSupplierId());
        procedure.setFinalizedCurrencyCode(saved.getCurrencyCode());
        procedure.setStatus(ProcurementProcedureStatus.DIRECT_AWARD_APPROVED.name());
        procedureRepository.save(procedure);
        auditService.log("ProcurementDirectAward", entityId, "APPROVE", comment == null ? saved.getCode() : comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        ProcurementDirectAward award = find(entityId);
        if (!"APPROVAL_PENDING".equals(award.getStatus())) {
            throw new BusinessException("Direct award must be APPROVAL_PENDING, current status is " + award.getStatus());
        }
        if (comment == null || comment.isBlank()) {
            throw new BusinessException("rejectionReason is required");
        }
        award.setStatus("REJECTED");
        repository.save(award);
        auditService.log("ProcurementDirectAward", entityId, "REJECT", comment, null, null);
    }

    @Override
    public void onReturned(UUID entityId, String comment) {
        ProcurementDirectAward award = find(entityId);
        if ("RETURNED".equals(award.getStatus())) {
            return;
        }
        if (!"APPROVAL_PENDING".equals(award.getStatus())) {
            throw new BusinessException("Direct award cannot be returned from status " + award.getStatus());
        }
        award.setStatus("RETURNED");
        repository.save(award);
        auditService.log("ProcurementDirectAward", entityId, "RETURN", comment, null, null);
    }

    private ProcurementProcedure requireDirectAwardProcedure(UUID id) {
        ProcurementProcedure procedure = procedureRepository.lockScopedById(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            id
        ).orElseThrow(() -> new NotFoundException("ProcurementProcedure not found: " + id));
        if (methodPolicy.require(procedure.getMethod()).executionPath() != ProcurementExecutionPath.DIRECT_AWARD) {
            throw new BusinessException("Procurement method does not use direct award execution: " + procedure.getMethod());
        }
        return procedure;
    }

    private ProcurementDirectAward find(UUID id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .filter(award -> TenantContext.currentTenant().equals(award.getTenantId()))
            .filter(award -> CompanyContext.currentCompany().equals(award.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ProcurementDirectAward not found: " + id));
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }

    private void requireGovernanceEvidence(ProcurementDirectAward award, ProcurementMethodType method) {
        requireText(award.getAwardBasis(), "awardBasisExplanation");
        requireText(award.getEvidenceReference(), "evidenceReference");
        if (award.getMarketPriceAmount() == null || award.getMarketPriceAmount().signum() <= 0) {
            throw new BusinessException("marketPriceAmount must be greater than zero");
        }
        requireText(award.getMarketPriceEvidenceReference(), "marketPriceEvidenceReference");
        requireText(award.getConflictOfInterestDeclaration(), "conflictOfInterestDeclaration");
        if (!award.isConflictOfInterestConfirmed()) {
            throw new BusinessException("conflictOfInterestConfirmed must be true before direct award submission");
        }
        if (award.getAwardAmount().compareTo(award.getMarketPriceAmount()) > 0) {
            throw new BusinessException("awardAmount cannot exceed the documented marketPriceAmount");
        }
        if (method == ProcurementMethodType.DIRECT_PURCHASE
            && !"MARKET_RESEARCH".equals(award.getAwardBasisType())) {
            throw new BusinessException("DIRECT_PURCHASE requires MARKET_RESEARCH awardBasisType");
        }
        if (method == ProcurementMethodType.SINGLE_SOURCE) {
            if ("MARKET_RESEARCH".equals(award.getAwardBasisType())) {
                throw new BusinessException("SINGLE_SOURCE requires EXCLUSIVITY, URGENCY or APPROVED_EXCEPTION awardBasisType");
            }
            requireText(award.getNegotiationSummary(), "negotiationSummary");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().length() < 3) {
            throw new BusinessException(field + " is required for direct award approval");
        }
    }
}

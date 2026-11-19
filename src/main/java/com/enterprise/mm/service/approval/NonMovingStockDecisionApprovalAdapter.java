package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.enums.NonMovingStockCaseStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.NonMovingStockCaseRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NonMovingStockNotificationService;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NonMovingStockDecisionApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "NON_MOVING_STOCK_DECISION";

    private final NonMovingStockDecisionRepository decisionRepository;
    private final NonMovingStockCaseRepository caseRepository;
    private final AuditService auditService;
    private final NonMovingStockNotificationService notificationService;



    @Override
    public String moduleKey() {
        return MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return findDecision(entityId).getCompanyId();
    }

    @Override
    public String displayName(UUID entityId) {
        NonMovingStockDecision decision = findDecision(entityId);
        NonMovingStockCase stockCase = findCase(decision.getCaseId());
        return "Non-moving stock " + decision.getDecisionType() + " decision";
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        NonMovingStockDecision decision = findDecision(entityId);
        if (!NonMovingStockCaseStatus.APPROVAL_PENDING.name().equals(decision.getStatus())) {
            throw new BusinessException("Non-moving stock decision must be APPROVAL_PENDING before approval start");
        }
    }

    @Override
    @Transactional
    public void markSubmitted(UUID entityId) {
        NonMovingStockDecision decision = findDecision(entityId);
        if (!NonMovingStockCaseStatus.APPROVAL_PENDING.name().equals(decision.getStatus())) {
            decision.setStatus(NonMovingStockCaseStatus.APPROVAL_PENDING.name());
            decisionRepository.save(decision);
        }
        NonMovingStockCase stockCase = findCase(decision.getCaseId());
        stockCase.setStatus(NonMovingStockCaseStatus.APPROVAL_PENDING.name());
        caseRepository.save(stockCase);
        auditService.log("NonMovingStockCase", stockCase.getId(), "SUBMIT_FOR_APPROVAL", decision.getDecisionType(), null, null);
        notificationService.notifyOwner(stockCase, "APPROVAL_PENDING", "Decision submitted for approval");
    }

    @Override
    @Transactional
    public void markApproved(UUID entityId, String comment) {
        NonMovingStockDecision decision = findDecision(entityId);
        NonMovingStockCase stockCase = findCase(decision.getCaseId());
        if (!NonMovingStockCaseStatus.APPROVAL_PENDING.name().equals(stockCase.getStatus())) {
            throw new BusinessException("Non-moving stock case must be APPROVAL_PENDING, current status is " + stockCase.getStatus());
        }
        decision.setStatus(NonMovingStockCaseStatus.DECISION_APPROVED.name());
        decisionRepository.save(decision);
        stockCase.setStatus(NonMovingStockCaseStatus.DECISION_APPROVED.name());
        caseRepository.save(stockCase);
        auditService.log("NonMovingStockCase", stockCase.getId(), "DECISION_APPROVED", comment, null, null);
        notificationService.notifyOwner(stockCase, "DECISION_APPROVED", comment);
    }

    @Override
    @Transactional
    public void markRejected(UUID entityId, String comment) {
        NonMovingStockDecision decision = findDecision(entityId);
        NonMovingStockCase stockCase = findCase(decision.getCaseId());
        decision.setStatus("REJECTED");
        decisionRepository.save(decision);
        stockCase.setStatus(NonMovingStockCaseStatus.UNDER_REVIEW.name());
        caseRepository.save(stockCase);
        auditService.log("NonMovingStockCase", stockCase.getId(), "DECISION_REJECTED", comment, null, null);
        notificationService.notifyOwner(stockCase, "DECISION_REJECTED", comment);
    }

    private NonMovingStockDecision findDecision(UUID id) {
        return decisionRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .orElseThrow(() -> new NotFoundException("NonMovingStockDecision not found: " + id));
    }

    private NonMovingStockCase findCase(UUID id) {
        return caseRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .orElseThrow(() -> new NotFoundException("NonMovingStockCase not found: " + id));
    }
}

package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ServiceReviewHistoryService;
import com.enterprise.mm.service.ServiceOrderAcceptanceLifecycleService;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServiceFinanceApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "SERVICE_FINANCE_APPROVAL";

    private final ServiceAcceptanceActRepository repository;
    private final AuditService auditService;
    private final ServiceReviewHistoryService historyService;
    private final ServiceOrderAcceptanceLifecycleService orderLifecycleService;



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
        ServiceAcceptanceAct act = find(entityId);
        return "Service finance approval";
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        ServiceAcceptanceAct act = find(entityId);
        if (!ServiceAcceptanceActStatus.FINANCE_REVIEW.name().equals(act.getStatus())) {
            throw new BusinessException("Service act must be FINANCE_REVIEW, current status is " + act.getStatus());
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        ServiceAcceptanceAct act = find(entityId);
        act.setStatus(ServiceAcceptanceActStatus.FINANCE_REVIEW.name());
        repository.save(act);
        orderLifecycleService.recompute(act.getServiceOrderId());
        historyService.record(act.getServiceRequestId(), act.getId(), "FINANCE", "SUBMITTED", null);
        auditService.log("ServiceAcceptanceAct", entityId, "FINANCE_SUBMIT", act.getCode(), null, null);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        ServiceAcceptanceAct act = find(entityId);
        if (!ServiceAcceptanceActStatus.FINANCE_REVIEW.name().equals(act.getStatus())) {
            throw new BusinessException("Service act must be FINANCE_REVIEW, current status is " + act.getStatus());
        }
        act.setStatus(ServiceAcceptanceActStatus.APPROVED.name());
        act.setFinanceReviewedAt(LocalDateTime.now());
        repository.save(act);
        orderLifecycleService.recompute(act.getServiceOrderId());
        historyService.record(act.getServiceRequestId(), act.getId(), "FINANCE", "APPROVED", comment);
        auditService.log("ServiceAcceptanceAct", entityId, "FINANCE_APPROVE", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        ServiceAcceptanceAct act = find(entityId);
        if (!ServiceAcceptanceActStatus.FINANCE_REVIEW.name().equals(act.getStatus())) {
            throw new BusinessException("Service act must be FINANCE_REVIEW, current status is " + act.getStatus());
        }
        act.setStatus(ServiceAcceptanceActStatus.REJECTED.name());
        repository.save(act);
        orderLifecycleService.recompute(act.getServiceOrderId());
        historyService.record(act.getServiceRequestId(), act.getId(), "FINANCE", "REJECTED", comment);
        auditService.log("ServiceAcceptanceAct", entityId, "FINANCE_REJECT", comment, null, null);
    }

    private ServiceAcceptanceAct find(UUID id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .filter(act -> TenantContext.currentTenant().equals(act.getTenantId()))
            .orElseThrow(() -> new NotFoundException("ServiceAcceptanceAct not found: " + id));
    }
}

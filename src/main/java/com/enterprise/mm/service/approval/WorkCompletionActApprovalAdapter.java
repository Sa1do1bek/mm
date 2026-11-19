package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.WorkCompletionActStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.WorkCompletionActService;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkCompletionActApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "WORK_COMPLETION_ACT";
    private static final Set<String> SUBMITTABLE = Set.of(
        WorkCompletionActStatus.DRAFT.name(),
        WorkCompletionActStatus.REJECTED.name()
    );

    private final WorkCompletionActRepository repository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final WorkCompletionActService service;
    private final AuditService auditService;



    @Override
    public String moduleKey() {
        return MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return purchaseOrder(entityId).getCompanyId();
    }

    @Override
    public UUID departmentId(UUID entityId) {
        PurchaseOrder order = purchaseOrder(entityId);
        return order.getPurchaseRequest() != null
            ? order.getPurchaseRequest().getDepartmentId()
            : null;
    }

    @Override
    public String displayName(UUID entityId) {
        WorkCompletionAct act = find(entityId);
        return act.getName();
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        WorkCompletionAct act = find(entityId);
        if (!SUBMITTABLE.contains(act.getStatus())) {
            throw new BusinessException("Work completion act must be DRAFT or REJECTED, current status is " + act.getStatus());
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        WorkCompletionAct act = find(entityId);
        act.setStatus(WorkCompletionActStatus.APPROVAL_PENDING.name());
        repository.save(act);
        auditService.log("WorkCompletionAct", entityId, "SUBMIT_FOR_APPROVAL", act.getCode(), null, null);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        service.approveFromWorkflow(entityId, comment);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        service.rejectFromWorkflow(entityId, comment);
    }

    private WorkCompletionAct find(UUID id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .filter(act -> TenantContext.currentTenant().equals(act.getTenantId()))
            .orElseThrow(() -> new NotFoundException("WorkCompletionAct not found: " + id));
    }

    private PurchaseOrder purchaseOrder(UUID entityId) {
        WorkCompletionAct act = find(entityId);
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(act.getPurchaseOrderId())
            .filter(order -> TenantContext.currentTenant().equals(order.getTenantId()))
            .orElseThrow(() -> new NotFoundException("PurchaseOrder not found: " + act.getPurchaseOrderId()));
    }
}

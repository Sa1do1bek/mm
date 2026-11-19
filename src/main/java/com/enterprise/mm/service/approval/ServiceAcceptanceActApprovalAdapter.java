package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ServiceReviewHistoryService;
import com.enterprise.mm.service.ServiceOrderAcceptanceLifecycleService;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServiceAcceptanceActApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "SERVICE_ACCEPTANCE_ACT";
    private static final Set<String> SUBMITTABLE = Set.of(ServiceAcceptanceActStatus.DRAFT.name(), "RETURNED");

    private final ServiceAcceptanceActRepository repository;
    private final ServiceRequestRepository requestRepository;
    private final AuditService auditService;
    private final ServiceReviewHistoryService historyService;
    private final AttachmentService attachmentService;
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
    public UUID departmentId(UUID entityId) {
        ServiceAcceptanceAct act = find(entityId);
        if (act.getServiceRequestId() != null) {
            return requestRepository.findByIdAndIsDeletedFalse(act.getServiceRequestId())
                .map(com.enterprise.mm.entity.ServiceRequest::getDepartmentId)
                .orElse(null);
        }
        return act.getServiceOrder() != null
            && act.getServiceOrder().getPurchaseOrder() != null
            && act.getServiceOrder().getPurchaseOrder().getPurchaseRequest() != null
            ? act.getServiceOrder().getPurchaseOrder().getPurchaseRequest().getDepartmentId()
            : null;
    }

    @Override
    public String displayName(UUID entityId) {
        ServiceAcceptanceAct act = find(entityId);
        return act.getName();
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        ServiceAcceptanceAct act = find(entityId);
        if (!SUBMITTABLE.contains(act.getStatus())) {
            throw new BusinessException("Service act must be DRAFT or RETURNED, current status is " + act.getStatus());
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        ServiceAcceptanceAct act = find(entityId);
        act.setStatus(ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name());
        repository.save(act);
        orderLifecycleService.recompute(act.getServiceOrderId());
        historyService.record(act.getServiceRequestId(), act.getId(), "TECHNICAL", "SUBMITTED", null);
        auditService.log("ServiceAcceptanceAct", entityId, "SUBMIT_FOR_APPROVAL", act.getCode(), null, null);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        ServiceAcceptanceAct act = find(entityId);
        if (!ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name().equals(act.getStatus())) {
            throw new BusinessException("Service act must be TECHNICAL_REVIEW, current status is " + act.getStatus());
        }
        validateTechnicalAttachments(act);
        act.setAcceptedAt(act.getAcceptedAt() == null ? LocalDateTime.now() : act.getAcceptedAt());
        act.setAcceptedBy(act.getAcceptedBy() == null ? "technical-review" : act.getAcceptedBy());
        act.setStatus(ServiceAcceptanceActStatus.DOCUMENT_REVIEW.name());
        repository.save(act);
        orderLifecycleService.recompute(act.getServiceOrderId());
        historyService.record(act.getServiceRequestId(), act.getId(), "TECHNICAL", "APPROVED", comment);
        auditService.log("ServiceAcceptanceAct", entityId, "TECHNICAL_APPROVE", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        ServiceAcceptanceAct act = find(entityId);
        if (!ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name().equals(act.getStatus())) {
            throw new BusinessException("Service act must be TECHNICAL_REVIEW, current status is " + act.getStatus());
        }
        act.setStatus(ServiceAcceptanceActStatus.REJECTED.name());
        repository.save(act);
        orderLifecycleService.recompute(act.getServiceOrderId());
        historyService.record(act.getServiceRequestId(), act.getId(), "TECHNICAL", "REJECTED", comment);
        auditService.log("ServiceAcceptanceAct", entityId, "TECHNICAL_REJECT", comment, null, null);
    }

    private ServiceAcceptanceAct find(UUID id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .filter(act -> TenantContext.currentTenant().equals(act.getTenantId()))
            .orElseThrow(() -> new NotFoundException("ServiceAcceptanceAct not found: " + id));
    }

    private void validateTechnicalAttachments(ServiceAcceptanceAct act) {
        if (!attachmentService.hasAttachment(MODULE_KEY, act.getId(), "ACT", act.getCompanyId())) {
            throw new BusinessException("ACT attachment is required before document review");
        }
        String serviceType = act.getServiceOrder() == null ? null : act.getServiceOrder().getServiceType();
        if (serviceType == null) {
            return;
        }
        String normalized = serviceType.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        boolean evidenceRequired = Set.of("WORK", "MAINTENANCE", "REPAIR").contains(normalized);
        if (evidenceRequired
            && !attachmentService.hasAttachment(MODULE_KEY, act.getId(), "CONTRACTOR_REPORT", act.getCompanyId())
            && !attachmentService.hasAttachment(MODULE_KEY, act.getId(), "PHOTO", act.getCompanyId())) {
            throw new BusinessException("Contractor report or photo attachment is required for " + normalized + " service acts");
        }
    }
}

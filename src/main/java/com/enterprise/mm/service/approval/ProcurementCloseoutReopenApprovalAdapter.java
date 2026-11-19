package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlowStep;
import com.enterprise.mm.entity.ProcurementCloseout;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.ProcurementCloseoutStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDecisionRequest;
import com.enterprise.mm.repository.ProcurementCloseoutRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.ProcurementCloseoutAuditHistoryService;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcurementCloseoutReopenApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "PROCUREMENT_CLOSEOUT_REOPEN";

    private final ProcurementCloseoutRepository repository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final AuditService auditService;
    private final SegregationOfDutyRuleService segregationOfDutyRuleService;
    private final ProcurementCloseoutAuditHistoryService closeoutAuditHistoryService;

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
        return purchaseRequest(find(entityId)).map(PurchaseRequest::getDepartmentId).orElse(null);
    }

    @Override
    public boolean usesRequestType() {
        return true;
    }

    @Override
    public RequestType requestType(UUID entityId) {
        ProcurementCloseout closeout = find(entityId);
        return purchaseOrder(closeout).map(PurchaseOrder::getRequestType)
            .or(() -> purchaseRequest(closeout).map(PurchaseRequest::getRequestType))
            .orElse(RequestType.GOODS);
    }

    @Override
    public String displayName(UUID entityId) {
        ProcurementCloseout closeout = find(entityId);
        return (closeout.getCode() == null || closeout.getCode().isBlank()) ? closeout.getName() : closeout.getCode();
    }

    @Override
    public List<ApprovalDetailItem> documentLines(UUID entityId) {
        ProcurementCloseout closeout = find(entityId);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("procurementProcedureId", closeout.getProcurementProcedureId());
        values.put("purchaseRequestId", closeout.getPurchaseRequestId());
        values.put("purchaseOrderId", closeout.getPurchaseOrderId());
        values.put("previousCloseoutStatus", closeout.getCloseoutStatus() == null ? null : closeout.getCloseoutStatus().name());
        values.put("reopenReason", closeout.getReopenReason());
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        return List.of(new ApprovalDetailItem(
            closeout.getId() == null ? null : closeout.getId().toString(),
            displayName(entityId),
            values
        ));
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        ProcurementCloseout closeout = find(entityId);
        if (closeout.getCloseoutStatus() != ProcurementCloseoutStatus.REOPEN_REQUESTED) {
            throw new BusinessException("Procurement closeout must be REOPEN_REQUESTED before reopen approval submission; current status is " + closeout.getCloseoutStatus());
        }
        if (closeout.getReopenReason() == null || closeout.getReopenReason().isBlank()) {
            throw new BusinessException("Reopen reason is required");
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        ProcurementCloseout closeout = find(entityId);
        if (closeout.getCloseoutStatus() == ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING) {
            return;
        }
        validateCanSubmit(entityId);
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING);
        closeout.setStatus(ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING.name());
        repository.save(closeout);
        closeoutAuditHistoryService.record(closeout, "SUBMIT_REOPEN", closeout.getReopenReason(), true);
        auditService.log("ProcurementCloseout", entityId, "SUBMIT_REOPEN_FOR_APPROVAL", closeout.getReopenReason(), null, null);
    }

    @Override
    public void beforeApproveTask(UUID entityId, ApprovalFlowStep step, ApprovalTaskDecisionRequest request) {
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("ProcurementCloseoutReopen", find(entityId), request == null ? null : request.comment());
        }
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        ProcurementCloseout closeout = find(entityId);
        if (closeout.getCloseoutStatus() == ProcurementCloseoutStatus.REOPENED) {
            return;
        }
        if (closeout.getCloseoutStatus() != ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING) {
            throw new BusinessException("Procurement closeout must be REOPEN_APPROVAL_PENDING, current status is " + closeout.getCloseoutStatus());
        }
        if (segregationOfDutyRuleService != null) {
            segregationOfDutyRuleService.validateApproval("ProcurementCloseoutReopen", closeout, comment);
        }
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.REOPENED);
        closeout.setStatus(ProcurementCloseoutStatus.REOPENED.name());
        closeout.setReopenedAt(LocalDateTime.now());
        closeout.setReopenedBy(currentActor());
        repository.save(closeout);
        closeoutAuditHistoryService.record(closeout, "APPROVE_REOPEN", comment, true);
        auditService.log("ProcurementCloseout", entityId, "APPROVE_REOPEN", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        ProcurementCloseout closeout = find(entityId);
        if (closeout.getCloseoutStatus() != ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING) {
            throw new BusinessException("Procurement closeout must be REOPEN_APPROVAL_PENDING, current status is " + closeout.getCloseoutStatus());
        }
        if (comment == null || comment.isBlank()) {
            throw new BusinessException("rejectionReason is required");
        }
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.CLOSED);
        closeout.setStatus(ProcurementCloseoutStatus.CLOSED.name());
        closeout.setCloseoutNote(comment.trim());
        repository.save(closeout);
        closeoutAuditHistoryService.record(closeout, "REJECT_REOPEN", comment, true);
        auditService.log("ProcurementCloseout", entityId, "REJECT_REOPEN", comment, null, null);
    }

    @Override
    public void onReturned(UUID entityId, String comment) {
        ProcurementCloseout closeout = find(entityId);
        if (closeout.getCloseoutStatus() == ProcurementCloseoutStatus.REOPEN_REQUESTED) {
            return;
        }
        if (closeout.getCloseoutStatus() != ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING) {
            throw new BusinessException("Procurement closeout reopen cannot be returned from status " + closeout.getCloseoutStatus());
        }
        closeout.setCloseoutStatus(ProcurementCloseoutStatus.REOPEN_REQUESTED);
        closeout.setStatus(ProcurementCloseoutStatus.REOPEN_REQUESTED.name());
        closeout.setCloseoutNote(comment);
        repository.save(closeout);
        closeoutAuditHistoryService.record(closeout, "RETURN_REOPEN", comment, true);
        auditService.log("ProcurementCloseout", entityId, "RETURN_REOPEN", comment, null, null);
    }

    @Override
    public Boolean isApprovalTaskActive(UUID entityId) {
        ProcurementCloseout closeout = find(entityId);
        return closeout.getCloseoutStatus() == ProcurementCloseoutStatus.REOPEN_APPROVAL_PENDING;
    }

    private ProcurementCloseout find(UUID id) {
        return repository.findById(id)
            .filter(closeout -> !closeout.isDeleted())
            .filter(closeout -> TenantContext.currentTenant().equals(closeout.getTenantId()))
            .filter(closeout -> com.enterprise.mm.security.AccessScope.canAccessCompany(closeout.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("ProcurementCloseout not found: " + id));
    }

    private java.util.Optional<PurchaseOrder> purchaseOrder(ProcurementCloseout closeout) {
        if (closeout.getPurchaseOrderId() == null) {
            return java.util.Optional.empty();
        }
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(closeout.getPurchaseOrderId())
            .filter(order -> TenantContext.currentTenant().equals(order.getTenantId()))
            .filter(order -> Objects.equals(order.getCompanyId(), closeout.getCompanyId()));
    }

    private java.util.Optional<PurchaseRequest> purchaseRequest(ProcurementCloseout closeout) {
        UUID purchaseRequestId = closeout.getPurchaseRequestId();
        if (purchaseRequestId == null) {
            purchaseRequestId = purchaseOrder(closeout).map(PurchaseOrder::getPurchaseRequestId).orElse(null);
        }
        if (purchaseRequestId == null) {
            return java.util.Optional.empty();
        }
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)
            .filter(request -> TenantContext.currentTenant().equals(request.getTenantId()))
            .filter(request -> Objects.equals(request.getCompanyId(), closeout.getCompanyId()));
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() || authentication.getName() == null ? "system" : authentication.getName();
    }
}

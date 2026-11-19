package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.enums.ServiceRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.BudgetItemService;
import com.enterprise.mm.service.ServiceReviewHistoryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServiceRequestApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "SERVICE_REQUEST";

    private final ServiceRequestRepository repository;
    private final AuditService auditService;
    private final ServiceReviewHistoryService historyService;
    private final BudgetItemService budgetItemService;



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
        return find(entityId).getDepartmentId();
    }

    @Override
    public String displayName(UUID entityId) {
        ServiceRequest request = find(entityId);
        return request.getTitle();
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        ServiceRequest request = find(entityId);
        if (!ServiceRequestStatus.DRAFT.name().equals(request.getStatus())
            && !ServiceRequestStatus.RETURNED.name().equals(request.getStatus())) {
            throw new BusinessException("Service request must be DRAFT or RETURNED, current status is " + request.getStatus());
        }
        if (request.getEstimatedAmount() != null && request.getEstimatedAmount().signum() < 0) {
            throw new BusinessException("estimatedAmount cannot be negative");
        }
    }

    @Override
    public void markSubmitted(UUID entityId) {
        ServiceRequest request = find(entityId);
        reserveBudgetIfNeeded(request);
        request.setStatus(ServiceRequestStatus.APPROVAL_PENDING.name());
        repository.save(request);
        auditService.log("ServiceRequest", entityId, "SUBMIT", request.getCode(), null, null);
    }

    @Override
    public void markApproved(UUID entityId, String comment) {
        ServiceRequest request = find(entityId);
        if (!ServiceRequestStatus.APPROVAL_PENDING.name().equals(request.getStatus())) {
            throw new BusinessException("Service request must be APPROVAL_PENDING, current status is " + request.getStatus());
        }
        request.setStatus(ServiceRequestStatus.APPROVED.name());
        if (request.getApprovedAmount() == null || request.getApprovedAmount().signum() == 0) {
            request.setApprovedAmount(request.getEstimatedAmount());
        }
        repository.save(request);
        historyService.record(entityId, null, "SERVICE_REQUEST", "APPROVED", comment);
        auditService.log("ServiceRequest", entityId, "APPROVE", comment, null, null);
    }

    @Override
    public void markRejected(UUID entityId, String comment) {
        ServiceRequest request = find(entityId);
        if (!ServiceRequestStatus.APPROVAL_PENDING.name().equals(request.getStatus())) {
            throw new BusinessException("Service request must be APPROVAL_PENDING, current status is " + request.getStatus());
        }
        request.setStatus(ServiceRequestStatus.REJECTED.name());
        releaseBudgetIfNeeded(request, "ServiceRequest " + request.getCode());
        repository.save(request);
        historyService.record(entityId, null, "SERVICE_REQUEST", "REJECTED", comment);
        auditService.log("ServiceRequest", entityId, "REJECT", comment, null, null);
    }

    private void reserveBudgetIfNeeded(ServiceRequest request) {
        BigDecimal amount = requestedBudgetAmount(request);
        if (request.getBudgetItemId() == null || amount.signum() <= 0) {
            return;
        }
        if (nvl(request.getBudgetReservedAmount()).compareTo(amount) != 0) {
            if (nvl(request.getBudgetReservedAmount()).signum() > 0) {
                budgetItemService.release(request.getBudgetItemId(), request.getBudgetReservedAmount(), "ServiceRequest " + request.getCode());
            }
            budgetItemService.reserve(request.getBudgetItemId(), amount, "ServiceRequest " + request.getCode());
            request.setBudgetReservedAmount(amount);
            request.setBudgetReservedAt(LocalDateTime.now());
            request.setBudgetReleasedAt(null);
        }
    }

    private void releaseBudgetIfNeeded(ServiceRequest request, String source) {
        if (request.getBudgetItemId() == null || nvl(request.getBudgetReservedAmount()).signum() <= 0) {
            return;
        }
        budgetItemService.release(request.getBudgetItemId(), request.getBudgetReservedAmount(), source);
        request.setBudgetReservedAmount(BigDecimal.ZERO);
        request.setBudgetReleasedAt(LocalDateTime.now());
    }

    private BigDecimal requestedBudgetAmount(ServiceRequest request) {
        BigDecimal approved = nvl(request.getApprovedAmount());
        return approved.signum() > 0 ? approved : nvl(request.getEstimatedAmount());
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private ServiceRequest find(UUID id) {
        return repository.findByIdAndIsDeletedFalse(id)
            .filter(request -> TenantContext.currentTenant().equals(request.getTenantId()))
            .orElseThrow(() -> new NotFoundException("ServiceRequest not found: " + id));
    }
}

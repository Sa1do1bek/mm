package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalOperationRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.ApprovalOperationRequestRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApprovalOperationExecutionService {
    private final ApprovalOperationRequestRepository repository;
    private final ApprovalOperationRegistry operations;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(UUID requestId) {
        ApprovalOperationRequest request = repository.findForUpdateByIdAndIsDeletedFalse(requestId)
            .orElseThrow(() -> new NotFoundException("Approval operation request not found"));
        requireScope(request);
        if ("APPLIED".equals(request.getStatus())) return;
        if (!"APPROVED_NOT_APPLIED".equals(request.getStatus()) && !"APPLY_FAILED".equals(request.getStatus())) {
            throw new BusinessException("Operation request is not approved for application");
        }
        ApprovedOperationContext.run(request.getModuleKey(), request.getTargetEntityId(),
            () -> operations.handler(request.getModuleKey()).apply(request));
        request.setStatus("APPLIED");
        request.setFailureReason(null);
        repository.save(request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID requestId, String reason) {
        ApprovalOperationRequest request = repository.findForUpdateByIdAndIsDeletedFalse(requestId)
            .orElseThrow(() -> new NotFoundException("Approval operation request not found"));
        requireScope(request);
        if (!"APPROVED_NOT_APPLIED".equals(request.getStatus()) && !"APPLY_FAILED".equals(request.getStatus())) return;
        request.setStatus("APPLY_FAILED");
        request.setFailureReason(reason == null ? "Application failed" : reason.substring(0, Math.min(reason.length(), 1000)));
        repository.save(request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandonFailed(UUID requestId, String requesterUsername) {
        ApprovalOperationRequest request = repository.findForUpdateByIdAndIsDeletedFalse(requestId)
            .orElseThrow(() -> new NotFoundException("Approval operation request not found"));
        requireScope(request);
        if (!request.getRequesterUsername().equalsIgnoreCase(requesterUsername)) {
            throw new BusinessException("Only the requester can abandon this failed operation");
        }
        if (!"APPLY_FAILED".equals(request.getStatus())) {
            throw new BusinessException("Only a failed, unapplied operation can be abandoned");
        }
        request.setStatus("ABANDONED");
        repository.save(request);
    }

    private void requireScope(ApprovalOperationRequest request) {
        if (!TenantContext.currentTenant().equals(request.getTenantId())
            || !CompanyContext.currentCompany().equals(request.getCompanyId())) {
            throw new NotFoundException("Approval operation request not found");
        }
    }
}

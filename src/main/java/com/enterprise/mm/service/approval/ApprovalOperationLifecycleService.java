package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalOperationRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.ApprovalOperationRequestRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApprovalOperationLifecycleService {
    private final ApprovalOperationRequestRepository repository;
    private final ApprovalOperationRegistry operations;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public ApprovalOperationRequest require(UUID requestId) {
        ApprovalOperationRequest request = repository.findByIdAndIsDeletedFalse(requestId)
            .orElseThrow(() -> new NotFoundException("Approval operation request not found"));
        if (!TenantContext.currentTenant().equals(request.getTenantId())
            || !CompanyContext.currentCompany().equals(request.getCompanyId())) {
            throw new NotFoundException("Approval operation request not found");
        }
        return request;
    }

    @Transactional
    public void submitted(UUID requestId) {
        ApprovalOperationRequest request = require(requestId);
        if (!"DRAFT".equals(request.getStatus()) && !"RETURNED".equals(request.getStatus())) {
            throw new BusinessException("Operation request is not ready for submission");
        }
        request.setStatus("PENDING");
        repository.save(request);
    }

    @Transactional
    public void approved(UUID requestId) {
        ApprovalOperationRequest request = require(requestId);
        if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessException("Operation request is not pending");
        }
        request.setStatus("APPROVED_NOT_APPLIED");
        repository.save(request);
        events.publishEvent(new ApprovalOperationEvent(requestId));
    }

    @Transactional
    public void rejected(UUID requestId) {
        ApprovalOperationRequest request = require(requestId);
        request.setStatus("REJECTED");
        repository.save(request);
    }

    @Transactional
    public void returned(UUID requestId) {
        ApprovalOperationRequest request = require(requestId);
        if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessException("Operation request is not pending");
        }
        request.setStatus("RETURNED");
        repository.save(request);
    }

    @Transactional
    public void cancelled(UUID requestId) {
        ApprovalOperationRequest request = require(requestId);
        if (!"DRAFT".equals(request.getStatus()) && !"PENDING".equals(request.getStatus())
            && !"RETURNED".equals(request.getStatus())) {
            throw new BusinessException("Only a pending or returned operation can be cancelled");
        }
        request.setStatus("CANCELLED");
        repository.save(request);
    }

    public ApprovalOperationHandler handler(UUID requestId) {
        ApprovalOperationRequest request = require(requestId);
        return operations.handler(request.getModuleKey());
    }
}

package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.enums.ManagementOpportunityStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.ManagementOpportunityRepository;
import com.enterprise.mm.service.AuditService;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ManagementOpportunityApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY = "MANAGEMENT_OPPORTUNITY";

    private final ManagementOpportunityRepository repository;
    private final AuditService auditService;



    @Override
    public String moduleKey() {
        return MODULE_KEY;
    }

    @Override
    public UUID companyId(UUID entityId) {
        return findEntity(entityId).getCompanyId();
    }

    @Override
    public String displayName(UUID entityId) {
        ManagementOpportunity entity = findEntity(entityId);
        return entity.getOpportunityType().name() + " management opportunity";
    }

    @Override
    public void validateCanSubmit(UUID entityId) {
        ManagementOpportunity entity = findEntity(entityId);
        if (!ManagementOpportunityStatus.DRAFT.name().equals(entity.getStatus())
            && !ManagementOpportunityStatus.READY_FOR_REVIEW.name().equals(entity.getStatus())) {
            throw new BusinessException("Management opportunity must be DRAFT before approval start");
        }
    }

    @Override
    @Transactional
    public void markSubmitted(UUID entityId) {
        ManagementOpportunity entity = findEntity(entityId);
        if (ManagementOpportunityStatus.APPROVED.name().equals(entity.getStatus())) {
            throw new BusinessException("Approved management opportunity cannot be resubmitted");
        }
        entity.setStatus(ManagementOpportunityStatus.READY_FOR_REVIEW.name());
        entity.setSubmittedAt(LocalDateTime.now());
        entity.setSubmittedBy(currentUsername());
        repository.save(entity);
        auditService.log("ManagementOpportunity", entityId, "SUBMIT_REVIEW", entity.getCode(), null, null);
    }

    @Override
    @Transactional
    public void markApproved(UUID entityId, String comment) {
        ManagementOpportunity entity = findEntity(entityId);
        if (!ManagementOpportunityStatus.READY_FOR_REVIEW.name().equals(entity.getStatus())) {
            throw new BusinessException("Management opportunity must be READY_FOR_REVIEW before approval");
        }
        entity.setStatus(ManagementOpportunityStatus.APPROVED.name());
        entity.setReviewedAt(LocalDateTime.now());
        entity.setReviewedBy(currentUsername());
        entity.setReviewComment(comment);
        entity.setApprovedAt(LocalDateTime.now());
        entity.setApprovedBy(currentUsername());
        repository.save(entity);
        auditService.log("ManagementOpportunity", entityId, "APPROVE", comment, null, null);
    }

    @Override
    @Transactional
    public void markRejected(UUID entityId, String comment) {
        ManagementOpportunity entity = findEntity(entityId);
        if (!ManagementOpportunityStatus.READY_FOR_REVIEW.name().equals(entity.getStatus())) {
            throw new BusinessException("Management opportunity must be READY_FOR_REVIEW before rejection");
        }
        entity.setStatus(ManagementOpportunityStatus.REJECTED.name());
        entity.setReviewedAt(LocalDateTime.now());
        entity.setReviewedBy(currentUsername());
        entity.setRejectionReason(comment);
        repository.save(entity);
        auditService.log("ManagementOpportunity", entityId, "REJECT", comment, null, null);
    }

    private ManagementOpportunity findEntity(UUID entityId) {
        return repository.findByIdAndIsDeletedFalse(entityId)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .orElseThrow(() -> new NotFoundException("ManagementOpportunity not found: " + entityId));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }
}

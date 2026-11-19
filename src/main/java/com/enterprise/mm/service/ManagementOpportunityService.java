package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.entity.ApprovalTask;
import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ManagementOpportunityMapper;
import com.enterprise.mm.payload.ManagementOpportunityResponse;
import com.enterprise.mm.payload.ManagementOpportunitySearchRequest;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.ApprovalTaskRepository;
import com.enterprise.mm.repository.ManagementOpportunityEvidenceRepository;
import com.enterprise.mm.repository.ManagementOpportunityRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ManagementOpportunityApprovalAdapter;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagementOpportunityService {
    private final ManagementOpportunityRepository repository;
    private final ManagementOpportunityEvidenceRepository evidenceRepository;
    private final ApprovalInstanceRepository approvalInstanceRepository;
    private final ApprovalTaskRepository approvalTaskRepository;
    private final ManagementOpportunityMapper mapper;
    private final ApprovalEngineService approvalEngineService;
    private final AuditService auditService;



    @Transactional(readOnly = true)
    public Page<ManagementOpportunityResponse> search(ManagementOpportunitySearchRequest request, Pageable pageable) {
        ManagementOpportunitySearchRequest filter = request == null
            ? new ManagementOpportunitySearchRequest(null, null, null, null, null, null, null, null, null, null, null, null, false)
            : request;
        return repository.search(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            filter.includeArchived(),
            filter.type(),
            filter.status(),
            filter.completeness(),
            filter.sourceAggregateType(),
            filter.sourceAggregateId(),
            filter.supplierId(),
            filter.materialId(),
            filter.warehouseId(),
            filter.confidence(),
            blankToNull(filter.assignedRole()),
            filter.asOfDateFrom(),
            filter.asOfDateTo(),
            pageable
        ).map(entity -> mapper.toResponse(entity, evidence(entity.getId())));
    }

    @Transactional(readOnly = true)
    public ManagementOpportunityResponse get(UUID id) {
        ManagementOpportunity entity = findEntity(id);
        return mapper.toResponse(entity, evidence(id));
    }

    @Transactional(readOnly = true)
    public Page<ManagementOpportunityResponse> byCalculationRun(UUID runId, Pageable pageable) {
        return repository.findByTenantIdAndCompanyIdAndCalculationRunIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            runId,
            pageable
        ).map(entity -> mapper.toResponse(entity, evidence(entity.getId())));
    }

    @Transactional
    public ManagementOpportunityResponse submitReview(UUID id) {
        ManagementOpportunity entity = findEntity(id);
        requireStatus(entity, ManagementOpportunityStatus.DRAFT);
        if (entity.getDataCompleteness() != ManagementOpportunityDataCompleteness.COMPLETE) {
            throw new BusinessException("Only complete management opportunity cases can be submitted for review");
        }
        if (entity.getNormalizedAmount() == null || entity.getNormalizedAmount().signum() <= 0) {
            throw new BusinessException("Positive normalized management estimate is required for review");
        }
        if (evidence(entity.getId()).isEmpty()) {
            throw new BusinessException("Evidence is required before management opportunity review");
        }
        approvalEngineService.start(ManagementOpportunityApprovalAdapter.MODULE_KEY, id);
        return get(id);
    }

    @Transactional
    public ManagementOpportunityResponse approve(UUID id, String comment) {
        ManagementOpportunity entity = findEntity(id);
        requireStatus(entity, ManagementOpportunityStatus.READY_FOR_REVIEW);
        ApprovalTask task = currentApprovalTask(entity);
        approvalEngineService.approveTask(task.getId(), comment);
        return get(id);
    }

    @Transactional
    public ManagementOpportunityResponse reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Rejection reason is required");
        }
        ManagementOpportunity entity = findEntity(id);
        requireStatus(entity, ManagementOpportunityStatus.READY_FOR_REVIEW);
        ApprovalTask task = currentApprovalTask(entity);
        approvalEngineService.rejectTask(task.getId(), reason);
        return get(id);
    }

    @Transactional
    public ManagementOpportunityResponse archive(UUID id) {
        ManagementOpportunity entity = findEntity(id);
        if (ManagementOpportunityStatus.APPROVED.name().equals(entity.getStatus())) {
            throw new BusinessException("Approved management opportunity cannot be archived");
        }
        entity.setStatus(ManagementOpportunityStatus.ARCHIVED.name());
        ManagementOpportunity saved = repository.save(entity);
        auditService.log("ManagementOpportunity", id, "ARCHIVE", entity.getCode(), null, null);
        return mapper.toResponse(saved, evidence(id));
    }

    @Transactional(readOnly = true)
    public ManagementOpportunity findEntity(UUID id) {
        ManagementOpportunity entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("ManagementOpportunity not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException("ManagementOpportunity not found: " + id);
        }
        if (!Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId())) {
            throw new NotFoundException("ManagementOpportunity not found: " + id);
        }
        return entity;
    }

    private void requireStatus(ManagementOpportunity entity, ManagementOpportunityStatus expected) {
        if (!expected.name().equals(entity.getStatus())) {
            throw new BusinessException("Unsupported management opportunity status transition from " + entity.getStatus());
        }
    }

    private java.util.List<com.enterprise.mm.entity.ManagementOpportunityEvidence> evidence(UUID id) {
        return evidenceRepository.findByTenantIdAndOpportunityIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), id);
    }

    private ApprovalTask currentApprovalTask(ManagementOpportunity entity) {
        ApprovalInstance instance = approvalInstanceRepository
            .findFirstByTenantIdAndModuleKeyAndEntityIdAndApprovalStatusAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                ManagementOpportunityApprovalAdapter.MODULE_KEY,
                entity.getId(),
                ApprovalInstanceStatus.PENDING
            )
            .filter(candidate -> Objects.equals(candidate.getCompanyId(), entity.getCompanyId()))
            .orElseThrow(() -> new BusinessException("No pending approval task exists for this management opportunity"));
        return approvalTaskRepository.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder(
                TenantContext.currentTenant(),
                instance.getId(),
                ApprovalTaskStatus.PENDING
            )
            .orElseThrow(() -> new BusinessException("No pending approval task exists for this management opportunity"));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

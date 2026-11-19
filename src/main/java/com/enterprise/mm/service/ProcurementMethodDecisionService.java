package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.enums.ProcurementMethodDecisionStatus;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ProcurementMethodDecisionMapper;
import com.enterprise.mm.payload.ProcurementMethodDecisionCreateRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionRejectRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionResponse;
import com.enterprise.mm.payload.ProcurementMethodDecisionSearchRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionUpdateRequest;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ProcurementMethodDecisionApprovalAdapter;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementMethodDecisionService {
    private static final String ENTITY_NAME = "ProcurementMethodDecision";

    private final ProcurementMethodDecisionRepository repository;
    private final ProcurementMethodDecisionMapper mapper;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseOrderDemandCyclePolicy demandCyclePolicy;
    private final AuditService auditService;
    private final ProcurementMethodPolicy methodPolicy;
    private final ProcurementMethodCapabilityService capabilityService;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final ApprovalEngineService approvalEngineService;
    private final CommissionRepository commissionRepository;
    private final ProcurementMethodDecisionActionPolicy actionPolicy;

    @Transactional
    public ProcurementMethodDecisionResponse create(ProcurementMethodDecisionCreateRequest request) {
        ProcurementMethodDecision entity = mapper.toEntity(request);
        normalize(entity);
        validateOptionalReferences(entity);
        initialize(entity);
        requireCapability(entity);
        ensureUnique(entity);
        ProcurementMethodDecision saved = repository.save(entity);
        approvalEngineService.linkItem(
            ProcurementMethodDecisionApprovalAdapter.MODULE_KEY,
            saved.getId(),
            saved.getCompanyId(),
            saved.getDecisionNumber()
        );
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE", saved.getDecisionNumber(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ProcurementMethodDecisionResponse update(UUID id, ProcurementMethodDecisionUpdateRequest request) {
        ProcurementMethodDecision entity = findEntity(id);
        if (ProcurementMethodDecisionStatus.APPROVED.name().equals(entity.getStatus())) {
            throw new BusinessException("Approved procurement method decision cannot be edited");
        }
        actionPolicy.requireActionAllowed(entity, "UPDATE");
        if (!ProcurementMethodDecisionStatus.DRAFT.name().equals(entity.getStatus())
            && !ProcurementMethodDecisionStatus.RETURNED.name().equals(entity.getStatus())) {
            throw new BusinessException("Only DRAFT or RETURNED procurement method decision can be edited");
        }
        mapper.updateEntity(entity, request);
        normalize(entity);
        validateOptionalReferences(entity);
        applyWorkflowNameFromPurchaseRequest(entity);
        requireCapability(entity);
        ensureUnique(entity);
        ProcurementMethodDecision saved = repository.save(entity);
        auditService.log(ENTITY_NAME, id, "UPDATE", saved.getDecisionNumber(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ProcurementMethodDecisionResponse submit(UUID id) {
        ProcurementMethodDecision entity = findEntity(id);
        actionPolicy.requireActionAllowed(entity, "SUBMIT");
        requireDemandPlanLineage(entity.getPurchaseRequestId());
        validateRequiredCommissionReference(entity);
        requireCapability(entity);
        approvalEngineService.start(ProcurementMethodDecisionApprovalAdapter.MODULE_KEY, id);
        return mapper.toResponse(findEntity(id));
    }

    @Transactional
    public ProcurementMethodDecisionResponse approve(UUID id) {
        throw new BusinessException("Procurement method decision approval must be completed through approval task endpoints");
    }

    @Transactional
    public ProcurementMethodDecisionResponse reject(UUID id, ProcurementMethodDecisionRejectRequest request) {
        throw new BusinessException("Procurement method decision rejection must be completed through approval task endpoints");
    }

    @Transactional
    public ProcurementMethodDecisionResponse revise(UUID id) {
        ProcurementMethodDecision entity = findEntity(id);
        actionPolicy.requireActionAllowed(entity, "REVISE");
        requireStatus(entity, ProcurementMethodDecisionStatus.REJECTED);
        entity.setStatus(ProcurementMethodDecisionStatus.DRAFT.name());
        entity.setSubmittedAt(null);
        entity.setRejectedAt(null);
        entity.setRejectedBy(null);
        entity.setRejectionReason(null);
        ProcurementMethodDecision saved = repository.save(entity);
        approvalEngineService.resetForResubmission(
            ProcurementMethodDecisionApprovalAdapter.MODULE_KEY,
            id,
            "Procurement method decision revised for resubmission"
        );
        auditService.log(ENTITY_NAME, id, "REVISE", entity.getDecisionNumber(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        ProcurementMethodDecision entity = findEntity(id);
        actionPolicy.requireActionAllowed(entity, "DELETE");
        if (!ProcurementMethodDecisionStatus.DRAFT.name().equals(entity.getStatus())
            && !ProcurementMethodDecisionStatus.REJECTED.name().equals(entity.getStatus())
            && !ProcurementMethodDecisionStatus.RETURNED.name().equals(entity.getStatus())) {
            throw new BusinessException("Procurement method decision in status " + entity.getStatus() + " cannot be deleted");
        }
        entity.softDelete();
        repository.save(entity);
        auditService.log(ENTITY_NAME, id, "SOFT_DELETE", entity.getDecisionNumber(), null, null);
    }

    @Transactional(readOnly = true)
    public ProcurementMethodDecisionResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public Page<ProcurementMethodDecisionResponse> search(ProcurementMethodDecisionSearchRequest request, Pageable pageable) {
        SearchCriteria criteria = SearchCriteria.of(request);
        var specification = ProcurementSearchSpecifications.build(
            ProcurementMethodDecision.class,
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            criteria,
            request
        );
        return repository.findAll(specification, pageable).map(mapper::toResponse);
    }

    protected ProcurementMethodDecision findEntity(UUID id) {
        ProcurementMethodDecision entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        return entity;
    }

    private void initialize(ProcurementMethodDecision entity) {
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode("PMD-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (!applyWorkflowNameFromPurchaseRequest(entity)) {
            entity.setName(BusinessDisplayNamePolicy.requireHumanName(ENTITY_NAME, entity.getName(), entity.getCode()));
        }
        if (entity.getDecisionNumber() == null || entity.getDecisionNumber().isBlank()) {
            entity.setDecisionNumber(entity.getCode());
        }
        entity.setStatus(ProcurementMethodDecisionStatus.DRAFT.name());
    }

    private void normalize(ProcurementMethodDecision entity) {
        entity.setSelectedMethod(methodPolicy.normalize(entity.getSelectedMethod()));
        entity.setCurrencyCode(entity.getCurrencyCode() == null || entity.getCurrencyCode().isBlank()
            ? "UZS"
            : entity.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        entity.setEstimatedAmount(entity.getEstimatedAmount() == null ? BigDecimal.ZERO : entity.getEstimatedAmount());
        entity.setPotentialSupplierCount(ProcurementMethodType.SINGLE_SOURCE.name().equals(entity.getSelectedMethod())
            ? 1
            : entity.getPotentialSupplierCount() == null ? 0 : entity.getPotentialSupplierCount());
        if (requiredCommissionType(entity.getSelectedMethod()) == null) {
            entity.setCommissionId(null);
        }
        entity.setDecisionNumber(trimToNull(entity.getDecisionNumber()));
        entity.setJustification(trimToNull(entity.getJustification()));
        entity.setExceptionReason(trimToNull(entity.getExceptionReason()));
    }

    private void validateOptionalReferences(ProcurementMethodDecision entity) {
        if (entity.getPurchaseRequestId() != null) {
            PurchaseRequest purchaseRequest = purchaseRequestRepository.findByIdAndIsDeletedFalse(entity.getPurchaseRequestId()).orElse(null);
            requireReference(purchaseRequest, "Purchase request", entity.getPurchaseRequestId());
            if (!ProcurementMethodDecisionStatus.APPROVED.name().equals(entity.getStatus())
                && !PurchaseRequestStatus.APPROVED.name().equals(purchaseRequest.getStatus())) {
                throw new BusinessException("Procurement method decision requires an APPROVED purchase request");
            }
            requireDemandPlanLineage(purchaseRequest.getId());
            repository.findFirstByTenantIdAndCompanyIdAndPurchaseRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getPurchaseRequestId()
            ).filter(existing -> !existing.getId().equals(entity.getId()))
                .ifPresent(existing -> {
                    throw new BusinessException("A current procurement method decision already exists for this purchase request");
                });
        }
        if (entity.getPurchaseRequestId() == null) {
            throw new BusinessException("Procurement method decision requires an approved demand-plan purchase request");
        }
        validateRequiredCommissionReference(entity);
    }

    private void validateRequiredCommissionReference(ProcurementMethodDecision entity) {
        CommissionType requiredType = requiredCommissionType(entity.getSelectedMethod());
        if (requiredType != null && entity.getCommissionId() == null) {
            throw new BusinessException("Procurement method decision requires an active " + requiredType.name() + " commission");
        }
        if (entity.getCommissionId() != null) {
            requireMethodCommission(entity.getCommissionId(), entity.getSelectedMethod());
        }
    }

    @Transactional(readOnly = true)
    public String previewName(UUID purchaseRequestId) {
        PurchaseRequest purchaseRequest = purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)
            .orElseThrow(() -> new BusinessException("Purchase request not found: " + purchaseRequestId));
        requireReference(purchaseRequest, "Purchase request", purchaseRequestId);
        return BusinessNameGenerator.workflowDocumentName("ProcurementMethodDecision", purchaseRequest.getName());
    }

    private boolean applyWorkflowNameFromPurchaseRequest(ProcurementMethodDecision entity) {
        if (entity.getPurchaseRequestId() == null) {
            return false;
        }
        entity.setName(previewName(entity.getPurchaseRequestId()));
        return true;
    }

    private void requireDemandPlanLineage(UUID purchaseRequestId) {
        if (purchaseRequestId == null) {
            throw new BusinessException("Procurement method decision requires an approved demand-plan purchase request");
        }
        if (demandCyclePolicy != null) {
            demandCyclePolicy.requireApprovedCycleForPurchaseRequest(purchaseRequestId);
        }
    }

    private void requireCapability(ProcurementMethodDecision entity) {
        String requestType = null;
        if (entity.getPurchaseRequestId() != null) {
            requestType = purchaseRequestRepository.findByIdAndIsDeletedFalse(entity.getPurchaseRequestId())
                .map(request -> request.getRequestType() == null ? null : request.getRequestType().name())
                .orElse(null);
        }
        // Category restrictions are evaluated by downstream category context; PMD itself has no category field.
        var categories = categories(entity.getPurchaseRequestId());
        if (categories.isEmpty()) {
            capabilityService.requireAvailableForCompany(entity.getSelectedMethod(), entity.getEstimatedAmount(), entity.getCurrencyCode(), requestType, null, entity.getCompanyId());
        } else {
            final String resolvedRequestType = requestType;
            categories.forEach(category -> capabilityService.requireAvailableForCompany(entity.getSelectedMethod(), entity.getEstimatedAmount(), entity.getCurrencyCode(), resolvedRequestType, category, entity.getCompanyId()));
        }
    }

    private java.util.Set<String> categories(UUID purchaseRequestId) {
        if (purchaseRequestId == null) return java.util.Set.of();
        return purchaseRequestItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(TenantContext.currentTenant(), entityCompany(purchaseRequestId), java.util.List.of(purchaseRequestId))
            .stream().map(item -> item.getMaterial() == null ? item.getServiceCategory() : item.getMaterial().getMaterialGroupId() == null
                ? item.getServiceCategory() : item.getMaterial().getMaterialGroupId().toString()).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private UUID entityCompany(UUID purchaseRequestId) {
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId).map(PurchaseRequest::getCompanyId).orElse(CompanyContext.currentCompany());
    }

    private void requireReference(BusinessEntity entity, String name, UUID id) {
        if (entity == null || entity.isDeleted()
            || !TenantContext.currentTenant().equals(entity.getTenantId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new BusinessException(name + " not found: " + id);
        }
    }

    private void requireMethodCommission(UUID commissionId, String method) {
        CommissionType requiredType = requiredCommissionType(method);
        if (requiredType == null) {
            return;
        }
        var commission = commissionRepository.findByIdAndIsDeletedFalse(commissionId)
            .orElseThrow(() -> new BusinessException(requiredType.name() + " commission not found: " + commissionId));
        if (!TenantContext.currentTenant().equals(commission.getTenantId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(commission.getCompanyId())) {
            throw new BusinessException(requiredType.name() + " commission not found: " + commissionId);
        }
        if (commission.getType() != requiredType || !commission.isActive()) {
            throw new BusinessException("Procurement method decision requires an active " + requiredType.name() + " commission");
        }
    }

    private CommissionType requiredCommissionType(String method) {
        String normalized = methodPolicy.normalize(method);
        if (ProcurementMethodType.OPEN_TENDER.name().equals(normalized)
            || ProcurementMethodType.CLOSED_TENDER.name().equals(normalized)) {
            return CommissionType.TENDER;
        }
        if (ProcurementMethodType.OPEN_COMPETITION.name().equals(normalized)
            || ProcurementMethodType.CLOSED_COMPETITION.name().equals(normalized)) {
            return CommissionType.COMPETITION;
        }
        return null;
    }

    private void ensureUnique(ProcurementMethodDecision entity) {
        if (entity.getDecisionNumber() == null) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndDecisionNumberAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getDecisionNumber()
        ).filter(existing -> !existing.getId().equals(entity.getId())).ifPresent(existing -> {
            throw new BusinessException("Procurement method decision number already exists");
        });
    }

    private void requireStatus(ProcurementMethodDecision entity, ProcurementMethodDecisionStatus status) {
        if (!status.name().equals(entity.getStatus())) {
            throw new BusinessException("Only " + status.name() + " procurement method decision can be processed");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

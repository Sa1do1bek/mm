package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.enums.ProcurementExecutionPath;
import com.enterprise.mm.enums.ProcurementMethodDecisionStatus;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ProcurementProcedureMapper;
import com.enterprise.mm.payload.ProcurementProcedureCancelRequest;
import com.enterprise.mm.payload.ProcurementProcedureResponse;
import com.enterprise.mm.payload.ProcurementProcedureSearchRequest;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementProcedureService {
    private static final String ENTITY_NAME = "ProcurementProcedure";

    private final ProcurementProcedureRepository repository;
    private final ProcurementProcedureMapper mapper;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final ProcurementMethodPolicy methodPolicy;
    private final PurchaseOrderDemandCyclePolicy demandCyclePolicy;
    private final ProcurementMethodCapabilityService capabilityService;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final ProcurementMethodDecisionRepository methodDecisionRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;

    @Transactional
    public ProcurementProcedureResponse createForApprovedDecision(ProcurementMethodDecision decision) {
        if (decision == null || !ProcurementMethodDecisionStatus.APPROVED.name().equals(decision.getStatus())) {
            throw new BusinessException("Procurement procedure requires an approved method decision");
        }
        if (demandCyclePolicy != null) {
            demandCyclePolicy.requireApprovedCycleForPurchaseRequest(decision.getPurchaseRequestId());
        }
        var existing = repository.findByMethodDecisionIdAndIsDeletedFalse(decision.getId());
        if (existing.isPresent()) {
            return mapper.toResponse(existing.get());
        }
        var capability = evaluateAllCategories(decision);
        if (!capability.available() && !grandfathered(decision.getCapabilityStateSnapshot(), decision.getCapabilityVersionSnapshot())) {
            throw new BusinessException(String.join(", ", capability.blockingReasons()), capability.blockingReasons().isEmpty() ? ProcurementMethodCapabilityService.NOT_CONFIGURED : capability.blockingReasons().get(0), capability.blockingReasons().toArray());
        }
        ProcurementProcedure entity = new ProcurementProcedure();
        entity.setCode(uniqueToken("PROC"));
        entity.setName(BusinessNameGenerator.workflowDocumentName("ProcurementProcedure", decision.getName()));
        entity.setStatus(ProcurementProcedureStatus.DRAFT.name());
        entity.setProcedureNumber("PROC-" + decision.getDecisionNumber());
        entity.setMethodDecisionId(decision.getId());
        entity.setPurchaseRequestId(decision.getPurchaseRequestId());
        ProcurementMethodType method = methodPolicy.require(decision.getSelectedMethod());
        entity.setMethod(method.name());
        entity.setExecutionPath(method.executionPath().name());
        entity.setRequiresPriceDiscovery(method.requiresPriceDiscovery());
        entity.setCapabilityStateSnapshot(decision.getCapabilityStateSnapshot());
        entity.setCapabilityVersionSnapshot(decision.getCapabilityVersionSnapshot());
        entity.setCompanyId(decision.getCompanyId());
        ProcurementProcedure saved = repository.save(entity);
        integrationEventService.emit("PROCUREMENT.PROCEDURE_CREATED", ENTITY_NAME, saved.getId(), Map.of(
            "procedureId", saved.getId(),
            "methodDecisionId", saved.getMethodDecisionId(),
            "method", saved.getMethod()
        ));
        auditService.log(ENTITY_NAME, saved.getId(), "CREATE_FROM_DECISION", decision.getId().toString(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ProcurementProcedureResponse ready(UUID id) {
        ProcurementProcedure entity = findEntity(id);
        requireStatus(entity, ProcurementProcedureStatus.DRAFT);
        entity.setStatus(ProcurementProcedureStatus.READY.name());
        auditService.log(ENTITY_NAME, id, "READY", entity.getProcedureNumber(), null, null);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse launch(UUID id) {
        ProcurementProcedure entity = findEntity(id);
        requireStatus(entity, ProcurementProcedureStatus.READY);
        var decision = methodDecisionRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(entity.getMethodDecisionId(), TenantContext.currentTenant(), entity.getCompanyId())
            .orElseThrow(() -> new BusinessException("Procurement method decision not found: " + entity.getMethodDecisionId()));
        if (!java.util.Objects.equals(decision.getCompanyId(), entity.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " method decision not found: " + entity.getMethodDecisionId());
        }
        var capability = evaluateAllCategories(decision);
        if (!capability.available() && !grandfathered(entity.getCapabilityStateSnapshot(), entity.getCapabilityVersionSnapshot())) {
            throw new BusinessException(String.join(", ", capability.blockingReasons()), capability.blockingReasons().isEmpty() ? ProcurementMethodCapabilityService.NOT_CONFIGURED : capability.blockingReasons().get(0), capability.blockingReasons().toArray());
        }
        ProcurementMethodType method = methodPolicy.require(entity.getMethod());
        ProcurementExecutionPath executionPath = method.executionPath();
        if (executionPath == ProcurementExecutionPath.RFQ
            || executionPath == ProcurementExecutionPath.TENDER_WITH_PRICE_DISCOVERY) {
            if (entity.getCampaignId() == null) {
                throw new BusinessException("campaignId is required before launching an RFQ procurement procedure");
            }
            entity.setStatus(ProcurementProcedureStatus.LAUNCHED.name());
        } else if (executionPath == ProcurementExecutionPath.DIRECT_AWARD) {
            entity.setStatus(ProcurementProcedureStatus.DIRECT_AWARD_DRAFT.name());
        } else if (executionPath == ProcurementExecutionPath.AUCTION) {
            entity.setStatus(ProcurementProcedureStatus.AUCTION_DRAFT.name());
        } else if (executionPath == ProcurementExecutionPath.FRAMEWORK_AGREEMENT) {
            entity.setStatus(ProcurementProcedureStatus.FRAMEWORK_DRAFT.name());
        } else {
            throw new BusinessException("Unsupported procurement execution path: " + executionPath);
        }
        entity.setLaunchedAt(LocalDateTime.now());
        ProcurementProcedure saved = repository.save(entity);
        integrationEventService.emit("PROCUREMENT.PROCEDURE_LAUNCHED", ENTITY_NAME, id, Map.of(
            "procedureId", id,
            "campaignId", entity.getCampaignId() == null ? "" : entity.getCampaignId(),
            "method", entity.getMethod(),
            "executionPath", executionPath.name()
        ));
        auditService.log(ENTITY_NAME, id, "LAUNCH", entity.getProcedureNumber(), null, null);
        return mapper.toResponse(saved);
    }

    @Transactional
    public ProcurementProcedureResponse complete(UUID id) {
        ProcurementProcedure entity = findEntity(id);
        if (!canComplete(entity)) {
            throw new BusinessException("Procurement procedure is not ready to complete");
        }
        entity.setStatus(ProcurementProcedureStatus.COMPLETED.name());
        entity.setCompletedAt(LocalDateTime.now());
        auditService.log(ENTITY_NAME, id, "COMPLETE", entity.getProcedureNumber(), null, null);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse cancel(UUID id, ProcurementProcedureCancelRequest request) {
        ProcurementProcedure entity = findEntity(id);
        if (ProcurementProcedureStatus.COMPLETED.name().equals(entity.getStatus())
            || ProcurementProcedureStatus.CANCELLED.name().equals(entity.getStatus())) {
            throw new BusinessException("Completed or cancelled procurement procedure cannot be cancelled");
        }
        String reason = request == null ? null : trimToNull(request.getCancellationReason());
        if (reason == null) {
            throw new BusinessException("cancellationReason is required");
        }
        entity.setStatus(ProcurementProcedureStatus.CANCELLED.name());
        entity.setCancelledAt(LocalDateTime.now());
        entity.setCancellationReason(reason);
        auditService.log(ENTITY_NAME, id, "CANCEL", reason, null, null);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse linkCampaign(UUID id, UUID campaignId) {
        if (campaignId == null) {
            throw new BusinessException("campaignId is required");
        }
        ProcurementProcedure entity = findEntity(id);
        if (entity.getCampaignId() != null && !entity.getCampaignId().equals(campaignId)) {
            throw new BusinessException("Procurement procedure is already linked to another campaign");
        }
        entity.setCampaignId(campaignId);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse linkRfq(UUID id, UUID rfqId) {
        if (rfqId == null) {
            throw new BusinessException("rfqId is required");
        }
        ProcurementProcedure entity = findEntity(id);
        if (!methodPolicy.require(entity.getMethod()).usesRfqEngine()) {
            throw new BusinessException("Procurement method does not use the RFQ engine: " + entity.getMethod());
        }
        if (entity.getRfqId() != null && !entity.getRfqId().equals(rfqId)) {
            throw new BusinessException("Procurement procedure is already linked to another RFQ");
        }
        entity.setRfqId(rfqId);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse linkDirectAward(UUID id, UUID directAwardId) {
        if (directAwardId == null) {
            throw new BusinessException("directAwardId is required");
        }
        ProcurementProcedure entity = findEntity(id);
        ProcurementMethodType method = methodPolicy.require(entity.getMethod());
        if (method.executionPath() != ProcurementExecutionPath.DIRECT_AWARD) {
            throw new BusinessException("Procurement method does not use direct award execution: " + entity.getMethod());
        }
        if (entity.getDirectAwardId() != null && !entity.getDirectAwardId().equals(directAwardId)) {
            throw new BusinessException("Procurement procedure is already linked to another direct award");
        }
        entity.setDirectAwardId(directAwardId);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse publishAuction(UUID id) {
        ProcurementProcedure entity = requireExecutionPath(id, ProcurementExecutionPath.AUCTION);
        requireStatus(entity, ProcurementProcedureStatus.AUCTION_DRAFT);
        entity.setStatus(ProcurementProcedureStatus.AUCTION_PUBLISHED.name());
        auditService.log(ENTITY_NAME, id, "AUCTION_PUBLISH", entity.getProcedureNumber(), null, null);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse startAuction(UUID id) {
        ProcurementProcedure entity = requireExecutionPath(id, ProcurementExecutionPath.AUCTION);
        requireStatus(entity, ProcurementProcedureStatus.AUCTION_PUBLISHED);
        entity.setStatus(ProcurementProcedureStatus.AUCTION_RUNNING.name());
        auditService.log(ENTITY_NAME, id, "AUCTION_START", entity.getProcedureNumber(), null, null);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse closeAuction(UUID id) {
        ProcurementProcedure entity = requireExecutionPath(id, ProcurementExecutionPath.AUCTION);
        requireStatus(entity, ProcurementProcedureStatus.AUCTION_RUNNING);
        entity.setStatus(ProcurementProcedureStatus.AUCTION_CLOSED.name());
        auditService.log(ENTITY_NAME, id, "AUCTION_CLOSE", entity.getProcedureNumber(), null, null);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse awardFramework(UUID id) {
        ProcurementProcedure entity = requireExecutionPath(id, ProcurementExecutionPath.FRAMEWORK_AGREEMENT);
        requireStatus(entity, ProcurementProcedureStatus.FRAMEWORK_DRAFT);
        entity.setStatus(ProcurementProcedureStatus.FRAMEWORK_AWARDED.name());
        auditService.log(ENTITY_NAME, id, "FRAMEWORK_AWARD", entity.getProcedureNumber(), null, null);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional
    public ProcurementProcedureResponse activateFramework(UUID id) {
        ProcurementProcedure entity = requireExecutionPath(id, ProcurementExecutionPath.FRAMEWORK_AGREEMENT);
        requireStatus(entity, ProcurementProcedureStatus.FRAMEWORK_AWARDED);
        entity.setStatus(ProcurementProcedureStatus.FRAMEWORK_ACTIVE.name());
        auditService.log(ENTITY_NAME, id, "FRAMEWORK_ACTIVATE", entity.getProcedureNumber(), null, null);
        return mapper.toResponse(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public ProcurementProcedureResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public Page<ProcurementProcedureResponse> search(ProcurementProcedureSearchRequest request, Pageable pageable) {
        SearchCriteria criteria = SearchCriteria.of(request);
        var specification = ProcurementSearchSpecifications.build(
            ProcurementProcedure.class,
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            criteria,
            request
        );
        return repository.findAll(specification, pageable).map(mapper::toResponse);
    }

    protected ProcurementProcedure findEntity(UUID id) {
        ProcurementProcedure entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(ENTITY_NAME + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(ENTITY_NAME + " not found: " + id);
        }
        return entity;
    }

    private void requireStatus(ProcurementProcedure entity, ProcurementProcedureStatus status) {
        if (!status.name().equals(entity.getStatus())) {
            throw new BusinessException("Only " + status.name() + " procurement procedure can be processed");
        }
    }

    private ProcurementProcedure requireExecutionPath(UUID id, ProcurementExecutionPath expected) {
        ProcurementProcedure entity = findEntity(id);
        ProcurementExecutionPath actual = methodPolicy.require(entity.getMethod()).executionPath();
        if (actual != expected) {
            throw new BusinessException("Procurement procedure does not use " + expected.name() + " execution");
        }
        return entity;
    }

    private boolean canComplete(ProcurementProcedure entity) {
        // AUCTION_CLOSED completes only through the auction award command, which records
        // the winner and creates the purchase order before marking the procedure COMPLETED.
        if (ProcurementProcedureStatus.AUCTION_CLOSED.name().equals(entity.getStatus())) {
            return entity.getPurchaseOrderId() != null;
        }
        return ProcurementProcedureStatus.LAUNCHED.name().equals(entity.getStatus())
            || ProcurementProcedureStatus.FRAMEWORK_ACTIVE.name().equals(entity.getStatus());
    }

    private String uniqueToken(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requestType(UUID purchaseRequestId) {
        if (purchaseRequestId == null) return null;
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId)
            .map(request -> request.getRequestType() == null ? null : request.getRequestType().name())
            .orElse(null);
    }

    private com.enterprise.mm.payload.ProcurementMethodCapabilityDtos.Evaluation evaluateAllCategories(ProcurementMethodDecision decision) {
        var categories = categories(decision);
        if (categories.isEmpty()) return capabilityService.evaluateForCompany(decision.getSelectedMethod(), decision.getEstimatedAmount(), decision.getCurrencyCode(), requestType(decision.getPurchaseRequestId()), null, decision.getCompanyId());
        com.enterprise.mm.payload.ProcurementMethodCapabilityDtos.Evaluation result = null;
        for (String category : categories) {
            result = capabilityService.evaluateForCompany(decision.getSelectedMethod(), decision.getEstimatedAmount(), decision.getCurrencyCode(), requestType(decision.getPurchaseRequestId()), category, decision.getCompanyId());
            if (!result.available()) return result;
        }
        return result;
    }

    private java.util.Set<String> categories(ProcurementMethodDecision decision) {
        if (decision.getPurchaseRequestId() == null) return java.util.Set.of();
        return purchaseRequestItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(TenantContext.currentTenant(), decision.getCompanyId(), java.util.List.of(decision.getPurchaseRequestId()))
            .stream().map(item -> item.getMaterial() == null ? item.getServiceCategory() : item.getMaterial().getMaterialGroupId() == null ? item.getServiceCategory() : item.getMaterial().getMaterialGroupId().toString())
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private boolean grandfathered(String state, Integer version) {
        if (state == null || state.isBlank() || version == null || version <= 0) return false;
        try { com.enterprise.mm.enums.ProcurementMethodCapabilityState.valueOf(state); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }
}

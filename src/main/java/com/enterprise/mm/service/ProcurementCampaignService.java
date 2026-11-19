package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ProcurementCampaignResponse;
import com.enterprise.mm.payload.ProcurementCampaignUpdateRequest;
import com.enterprise.mm.payload.ProcurementCampaignCreateRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ProcurementCampaignMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ProcurementCampaignStatus;
import com.enterprise.mm.enums.ProcurementCampaignType;
import com.enterprise.mm.enums.ProcurementMethodDecisionStatus;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateCampaignRfqRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.ProcurementCampaignSearchRequest;

@Service
@RequiredArgsConstructor
public class ProcurementCampaignService {
    private final String entityName = "ProcurementCampaign";
    private final ProcurementCampaignRepository repository;
    private final ProcurementCampaignMapper mapper;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final MaterialRepository materialRepository;
    private final RFQRepository rfqRepository;
    private final RFQService rfqService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final ProcurementMethodDecisionRepository methodDecisionRepository;
    private final ProcurementProcedureRepository procedureRepository;
    private final ProcurementProcedureService procedureService;
    private final ProcurementMethodPolicy methodPolicy;
    private final ProcurementCampaignRfqSourceService rfqSourceService;
    private final RfqAggregationService rfqAggregationService;
    private final PriceDiscoveryService priceDiscoveryService;
    private final TenderCommissionService tenderCommissionService;

    @Transactional
    public ProcurementCampaignResponse create(ProcurementCampaignCreateRequest dto) {
        ProcurementCampaign entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ProcurementCampaign savedEntity = repository.save(entity);
        procedureService.linkCampaign(savedEntity.getProcurementProcedureId(), savedEntity.getId());
        auditService.log(entityName, savedEntity.getId(), "CREATE", savedEntity.getCode(), null, null);
        return mapper.toResponse(savedEntity);
    }
    @Transactional
    public ProcurementCampaignResponse update(UUID id, ProcurementCampaignUpdateRequest dto) {
        ProcurementCampaign current = findEntity(id);
        if (ProcurementCampaignStatus.RFQ_CREATED.name().equals(current.getStatus()) || ProcurementCampaignStatus.COMPLETED.name().equals(current.getStatus()) || ProcurementCampaignStatus.CANCELLED.name().equals(current.getStatus())) {
            throw new BusinessException("RFQ_CREATED, COMPLETED or CANCELLED procurement campaign cannot be edited");
        }
        ProcurementCampaign entity = current;
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ProcurementCampaign savedEntity = repository.save(entity);
        procedureService.linkCampaign(savedEntity.getProcurementProcedureId(), savedEntity.getId());
        auditService.log(entityName, savedEntity.getId(), "UPDATE", savedEntity.getCode(), null, null);
        return mapper.toResponse(savedEntity);
    }
    @Transactional
    public void softDelete(UUID id) {
        ProcurementCampaign entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    protected void beforeCreate(ProcurementCampaign entity, ProcurementCampaignCreateRequest dto) {
        hydrate(entity);
        normalize(entity);
        validate(entity);
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            applyWorkflowNameFromProcedure(entity);
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(BusinessDisplayNamePolicy.requireHumanName(
                entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
            ));
        }

        if (entity.getCampaignNumber() == null || entity.getCampaignNumber().isBlank()) {
            entity.setCampaignNumber(entity.getCode());
        }
        ensureUnique(entity);
        entity.setStatus(ProcurementCampaignStatus.DRAFT.name());
    }
    protected void beforeUpdate(ProcurementCampaign entity, ProcurementCampaignUpdateRequest dto) {
        hydrate(entity);
        normalize(entity);
        validate(entity);
        ensureUnique(entity);
        if (entity.getName() == null || entity.getName().isBlank()) {
            applyWorkflowNameFromProcedure(entity);
        }
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(BusinessDisplayNamePolicy.requireHumanName(
                entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
            ));
        }
    }
    @Transactional
    public ProcurementCampaignResponse plan(UUID id) {
        ProcurementCampaign campaign = findEntity(id);
        requireStatus(campaign, ProcurementCampaignStatus.DRAFT);
        ProcurementProcedure procedure = requireApprovedBinding(campaign);
        if (ProcurementProcedureStatus.DRAFT.name().equals(procedure.getStatus())) {
            procedureService.ready(procedure.getId());
        }
        campaign.setStatus(ProcurementCampaignStatus.PLANNED.name());
        auditService.log("ProcurementCampaign", id, "PLAN", campaign.getCampaignNumber(), null, null);
        return mapper.toResponse(repository.save(campaign));
    }
    @Transactional
    public ProcurementCampaignResponse launch(UUID id) {
        ProcurementCampaign campaign = findEntity(id);
        if (!ProcurementCampaignStatus.DRAFT.name().equals(campaign.getStatus()) && !ProcurementCampaignStatus.PLANNED.name().equals(campaign.getStatus())) {
            throw new BusinessException("Only DRAFT or PLANNED procurement campaign can be launched");
        }
        ProcurementProcedure procedure = requireApprovedBinding(campaign);
        advanceProcedureForCampaignLaunch(procedure);
        campaign.setStatus(ProcurementCampaignStatus.LAUNCHED.name());
        campaign.setLaunchedAt(LocalDateTime.now());
        integrationEventService.emit("PROCUREMENT.CAMPAIGN_LAUNCHED", "ProcurementCampaign", id, campaignLaunchPayload(id, campaign));
        auditService.log("ProcurementCampaign", id, "LAUNCH", campaign.getCampaignNumber(), null, null);
        return mapper.toResponse(repository.save(campaign));
    }
    @Transactional
    public ProcurementCampaignResponse createRfq(UUID id) {
        return createRfq(id, null);
    }
    @Transactional
    public ProcurementCampaignResponse createRfq(UUID id, CreateCampaignRfqRequest request) {
        ProcurementCampaign campaign = findEntity(id);
        requireStatus(campaign, ProcurementCampaignStatus.LAUNCHED);
        ProcurementProcedure procedure = requireApprovedBinding(campaign);
        if (!ProcurementProcedureStatus.LAUNCHED.name().equals(procedure.getStatus())) {
            throw new BusinessException("Procurement procedure must be LAUNCHED before RFQ creation");
        }
        if (!methodPolicy.require(procedure.getMethod()).usesRfqEngine()) {
            throw new BusinessException("Procurement method does not use the RFQ engine: " + procedure.getMethod());
        }
        if (procedure.isRequiresPriceDiscovery()
            && (ProcurementMethodType.OPEN_TENDER.name().equals(procedure.getMethod())
                || ProcurementMethodType.CLOSED_TENDER.name().equals(procedure.getMethod()))) {
            throw new BusinessException("Open and closed tenders require finalized price discovery before tender creation");
        }
        if (campaign.getRfqId() != null) {
            throw new BusinessException("RFQ is already linked to procurement campaign");
        }
        boolean aggregatedRequest = request != null && request.hasAggregationSelection();
        // Campaigns bound to a specific purchase request have positions to copy automatically;
        // category/annual campaigns without one (see ProcurementStrategyPlanningTest) keep the
        // previous bare-RFQ behavior since there is nothing to derive lines from.
        PurchaseRequest purchaseRequest = campaign.getPurchaseRequestId() == null
            ? null
            : priceDiscoveryService.requireApprovedPurchaseRequest(campaign.getPurchaseRequestId());
        List<PurchaseRequestItem> requestItems = purchaseRequest == null
            ? List.of()
            : priceDiscoveryService.purchaseRequestItems(purchaseRequest.getId());
        if (!aggregatedRequest && purchaseRequest != null && requestItems.isEmpty()) {
            throw new BusinessException("Purchase request has no lines");
        }
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-" + campaign.getCampaignNumber());
        rfq.setName(campaign.getName());
        rfq.setStatus("OPEN");
        rfq.setPurpose("PROCUREMENT");
        rfq.setProcurementProcedureId(procedure.getId());
        rfq.setPurchaseRequestId(campaign.getPurchaseRequestId());
        rfq.setTenderProcedureType(procedure.getMethod());
        rfq.setSelectionRule(request == null || priceDiscoveryService.blank(request.selectionRule()) == null
            ? "BEST_SCORE"
            : request.selectionRule());
        rfq.setMinQuotationsRequired(request == null || request.minQuotationsRequired() == null
            ? Math.max(2, campaign.getTargetSupplierCount())
            : request.minQuotationsRequired());
        rfq.setDueDate(request == null || request.dueDate() == null
            ? priceDiscoveryService.defaultDueDate(campaign, 14)
            : request.dueDate());
        if (purchaseRequest != null) {
            rfq.setCurrencyCode(priceDiscoveryService.currency(purchaseRequest.getCurrencyCode()));
        }
        rfq.setPackageLanguage(request == null || priceDiscoveryService.blank(request.packageLanguage()) == null
            ? "ru"
            : request.packageLanguage().trim().toLowerCase(Locale.ROOT));
        String requestedBuyer = request == null ? null : priceDiscoveryService.blank(request.responsibleBuyer());
        rfq.setResponsibleBuyer(requestedBuyer == null ? currentActor() : requestedBuyer);
        rfq.setResponseInstructions(request == null ? null : priceDiscoveryService.blank(request.responseInstructions()));
        // createLinked applies the same beforeCreate pipeline as POST /api/rfq; positions and
        // supplier invitations are copied from the linked purchase request right after, so the
        // RFQ starts as a DRAFT the buyer only needs to review and publish, not fill in from scratch.
        rfq = rfqService.createLinked(rfq);
        if (aggregatedRequest) {
            rfqAggregationService.allocateToCampaignRfq(
                rfq,
                request.groupBy(),
                request.groupKey(),
                request.purchaseRequestItemIds());
        } else {
            rfqSourceService.copyPurchaseRequestLines(rfq);
        }
        Set<UUID> invitedSupplierIds = priceDiscoveryService.supplierIds(
            request == null ? List.of() : request.invitedSupplierIds(),
            priceDiscoveryService.isTenderMethod(procedure.getMethod()));
        priceDiscoveryService.syncInvitations(rfq, invitedSupplierIds);
        assignTenderCommissionFromDecision(rfq.getId(), procedure, request == null ? null : request.commissionId());
        campaign.setRfqId(rfq.getId());
        campaign.setProcedureType(procedure.getMethod());
        campaign.setStatus(ProcurementCampaignStatus.RFQ_CREATED.name());
        procedureService.linkRfq(procedure.getId(), rfq.getId());
        integrationEventService.emit("PROCUREMENT.CAMPAIGN_RFQ_CREATED", "ProcurementCampaign", id, Map.of(
            "campaignId", id,
            "rfqId", rfq.getId(),
            "procedureType", campaign.getProcedureType()
        ));
        auditService.log("ProcurementCampaign", id, "CREATE_RFQ", rfq.getId().toString(), null, null);
        auditService.log("RFQ", rfq.getId(), "CREATE_FROM_PROCUREMENT_CAMPAIGN", id.toString(), null, null);
        return mapper.toResponse(repository.save(campaign));
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }
    @Transactional
    public ProcurementCampaignResponse complete(UUID id) {
        ProcurementCampaign campaign = findEntity(id);
        if (!ProcurementCampaignStatus.RFQ_CREATED.name().equals(campaign.getStatus()) && !ProcurementCampaignStatus.LAUNCHED.name().equals(campaign.getStatus())) {
            throw new BusinessException("Only LAUNCHED or RFQ_CREATED procurement campaign can be completed");
        }
        campaign.setStatus(ProcurementCampaignStatus.COMPLETED.name());
        campaign.setCompletedAt(LocalDateTime.now());
        // plan/launch/create-rfq already drive the procedure forward; the terminal transitions
        // must close it too, otherwise the procedure stays LAUNCHED forever.
        linkedProcedure(campaign)
            .filter(procedure -> ProcurementProcedureStatus.LAUNCHED.name().equals(procedure.getStatus()))
            .ifPresent(procedure -> procedureService.complete(procedure.getId()));
        auditService.log("ProcurementCampaign", id, "COMPLETE", campaign.getCampaignNumber(), null, null);
        return mapper.toResponse(repository.save(campaign));
    }
    @Transactional
    public ProcurementCampaignResponse cancel(UUID id, String cancellationReason) {
        ProcurementCampaign campaign = findEntity(id);
        if (ProcurementCampaignStatus.COMPLETED.name().equals(campaign.getStatus())) {
            throw new BusinessException("Completed procurement campaign cannot be cancelled");
        }
        campaign.setStatus(ProcurementCampaignStatus.CANCELLED.name());
        String reason = cancellationReason == null || cancellationReason.isBlank()
            ? "Cancelled with procurement campaign " + campaign.getCampaignNumber()
            : cancellationReason;
        linkedProcedure(campaign)
            .filter(procedure -> !ProcurementProcedureStatus.COMPLETED.name().equals(procedure.getStatus())
                && !ProcurementProcedureStatus.CANCELLED.name().equals(procedure.getStatus()))
            .ifPresent(procedure -> {
                com.enterprise.mm.payload.ProcurementProcedureCancelRequest cancelRequest =
                    new com.enterprise.mm.payload.ProcurementProcedureCancelRequest();
                cancelRequest.setCancellationReason(reason);
                procedureService.cancel(procedure.getId(), cancelRequest);
            });
        auditService.log("ProcurementCampaign", id, "CANCEL", campaign.getCampaignNumber(), null, null);
        return mapper.toResponse(repository.save(campaign));
    }

    private java.util.Optional<ProcurementProcedure> linkedProcedure(ProcurementCampaign campaign) {
        if (campaign.getProcurementProcedureId() == null) {
            return java.util.Optional.empty();
        }
        return procedureRepository.findByIdAndIsDeletedFalse(campaign.getProcurementProcedureId())
            .filter(this::isAccessible);
    }

    private void advanceProcedureForCampaignLaunch(ProcurementProcedure procedure) {
        String status = procedure.getStatus();
        if (ProcurementProcedureStatus.DRAFT.name().equals(status)) {
            procedureService.ready(procedure.getId());
            procedureService.launch(procedure.getId());
            return;
        }
        if (ProcurementProcedureStatus.READY.name().equals(status)) {
            procedureService.launch(procedure.getId());
            return;
        }
        if (ProcurementProcedureStatus.LAUNCHED.name().equals(status)) {
            return;
        }
        throw new BusinessException(
            "Linked procurement procedure must be DRAFT, READY or LAUNCHED before campaign launch, current status: " + status
        );
    }

    private void hydrate(ProcurementCampaign entity) {
        requireApprovedBinding(entity);
    }
    private void normalize(ProcurementCampaign entity) {
        String defaultCampaignType = defaultCampaignType(entity.getProcedureType());
        entity.setCampaignType(normalizeEnum(entity.getCampaignType(), defaultCampaignType, enumNames(ProcurementCampaignType.values()), "campaignType"));
        entity.setCurrencyCode(normalizeToken(entity.getCurrencyCode(), "UZS"));
        entity.setTargetSupplierCount(entity.getTargetSupplierCount() == null ? 3 : entity.getTargetSupplierCount());
        entity.setPlannedSpendAmount(nvl(entity.getPlannedSpendAmount()));
        entity.setApprovedSpendAmount(nvl(entity.getApprovedSpendAmount()));
        entity.setSavingsTargetAmount(nvl(entity.getSavingsTargetAmount()));
        if (entity.getCampaignNumber() != null) {
            entity.setCampaignNumber(entity.getCampaignNumber().trim());
        }
    }

    private String defaultCampaignType(String procedureType) {
        String method = methodPolicy.normalize(procedureType);
        if (ProcurementMethodType.REQUEST_FOR_QUOTATION.name().equals(method)) {
            return ProcurementCampaignType.SPOT_BUY.name();
        }
        if (ProcurementMethodType.OPEN_COMPETITION.name().equals(method)
            || ProcurementMethodType.CLOSED_COMPETITION.name().equals(method)) {
            return ProcurementCampaignType.COMPETITION.name();
        }
        return ProcurementCampaignType.ANNUAL_TENDER.name();
    }
    private void validate(ProcurementCampaign entity) {
        ProcurementProcedure procedure = requireApprovedBinding(entity);
        if (entity.getPurchaseRequestId() != null) {
            PurchaseRequest pr = purchaseRequestRepository.findByIdAndIsDeletedFalse(entity.getPurchaseRequestId()).orElse(null);
            if (pr == null || pr.isDeleted() || !TenantContext.currentTenant().equals(pr.getTenantId()) || !com.enterprise.mm.security.AccessScope.canAccessCompany(pr.getCompanyId())) {
                throw new BusinessException("Purchase request not found: " + entity.getPurchaseRequestId());
            }
        }
        if (entity.getTargetSupplierCount() < 1) {
            throw new BusinessException("targetSupplierCount must be greater than zero");
        }
        if (methodPolicy.require(procedure.getMethod()).isCompetitive() && entity.getTargetSupplierCount() < 2) {
            throw new BusinessException("Competitive procurement campaign must target at least two suppliers");
        }
        if (entity.getPlannedSpendAmount().signum() < 0 || entity.getApprovedSpendAmount().signum() < 0 || entity.getSavingsTargetAmount().signum() < 0) {
            throw new BusinessException("Procurement campaign amounts cannot be negative");
        }
        if (entity.getPlannedStartDate() != null && entity.getPlannedEndDate() != null && entity.getPlannedEndDate().isBefore(entity.getPlannedStartDate())) {
            throw new BusinessException("plannedEndDate must be greater than or equal to plannedStartDate");
        }
    }

    private ProcurementProcedure requireApprovedBinding(ProcurementCampaign campaign) {
        if (campaign.getProcurementProcedureId() == null) {
            throw new BusinessException("procurementProcedureId is required");
        }
        ProcurementProcedure procedure = procedureRepository.findByIdAndIsDeletedFalse(campaign.getProcurementProcedureId())
            .filter(this::isAccessible)
            .orElseThrow(() -> new BusinessException("Procurement procedure not found: " + campaign.getProcurementProcedureId()));
        if (procedure.getMethodDecisionId() == null) {
            throw new BusinessException("Procurement procedure is missing method decision");
        }
        ProcurementMethodDecision decision = methodDecisionRepository.findByIdAndIsDeletedFalse(procedure.getMethodDecisionId())
            .filter(this::isAccessible)
            .orElseThrow(() -> new BusinessException("Procurement method decision not found: " + procedure.getMethodDecisionId()));
        if (!decision.getId().equals(procedure.getMethodDecisionId())) {
            throw new BusinessException("Procurement procedure does not belong to method decision");
        }
        if (!ProcurementMethodDecisionStatus.APPROVED.name().equals(decision.getStatus())) {
            throw new BusinessException("Campaign requires an APPROVED procurement method decision");
        }
        campaign.setMethodDecisionId(decision.getId());
        campaign.setProcedureType(methodPolicy.normalize(procedure.getMethod()));
        if (decision.getPotentialSupplierCount() != null && decision.getPotentialSupplierCount() > 0) {
            campaign.setTargetSupplierCount(decision.getPotentialSupplierCount());
        }
        campaign.setPurchaseRequestId(firstUuid(procedure.getPurchaseRequestId(), decision.getPurchaseRequestId()));
        defaultPlannedEndDateFromPurchaseRequest(campaign);
        campaign.setMaterialGroupId(materialGroupFromPurchaseRequest(campaign.getPurchaseRequestId()));
        return procedure;
    }

    private void assignTenderCommissionFromDecision(UUID rfqId, ProcurementProcedure procedure, UUID requestCommissionId) {
        UUID commissionId = requestCommissionId;
        if (commissionId == null && procedure.getMethodDecisionId() != null) {
            commissionId = methodDecisionRepository.findByIdAndIsDeletedFalse(procedure.getMethodDecisionId())
                .filter(this::isAccessible)
                .map(ProcurementMethodDecision::getCommissionId)
                .orElse(null);
        }
        if (commissionId != null) {
            tenderCommissionService.assignProcessCommission(rfqId, commissionId);
        }
    }

    private void defaultPlannedEndDateFromPurchaseRequest(ProcurementCampaign campaign) {
        if (campaign.getPlannedEndDate() != null || campaign.getPurchaseRequestId() == null) {
            return;
        }
        purchaseRequestRepository.findByIdAndIsDeletedFalse(campaign.getPurchaseRequestId())
            .filter(this::isAccessible)
            .map(PurchaseRequest::getRequiredDate)
            .ifPresent(campaign::setPlannedEndDate);
    }

    private boolean applyWorkflowNameFromProcedure(ProcurementCampaign entity) {
        if (entity.getProcurementProcedureId() == null) {
            return false;
        }
        ProcurementProcedure procedure = requireApprovedBinding(entity);
        entity.setName(BusinessNameGenerator.workflowDocumentName("ProcurementCampaign", procedure.getName()));
        return true;
    }

    private Map<String, Object> campaignLaunchPayload(UUID id, ProcurementCampaign campaign) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("campaignId", id);
        payload.put("campaignType", campaign.getCampaignType());
        payload.put("procedureType", campaign.getProcedureType());
        payload.put("plannedSpendAmount", nvl(campaign.getPlannedSpendAmount()));
        payload.put("currency", campaign.getCurrencyCode());
        return payload;
    }

    private boolean isAccessible(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId());
    }
    private void ensureUnique(ProcurementCampaign entity) {
        if (entity.getCampaignNumber() == null || entity.getCampaignNumber().isBlank()) {
            return;
        }
        repository.findByTenantIdAndCompanyIdAndCampaignNumberAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), entity.getCampaignNumber())
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Procurement campaign number already exists");
            });
    }
    private void requireStatus(ProcurementCampaign campaign, ProcurementCampaignStatus status) {
        if (!status.name().equals(campaign.getStatus())) {
            throw new BusinessException("Only " + status.name() + " procurement campaign can be processed");
        }
    }
    private UUID firstUuid(UUID... values) {
        for (UUID value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
    private UUID materialGroupFromPurchaseRequest(UUID purchaseRequestId) {
        if (purchaseRequestId == null) {
            return null;
        }
        Set<UUID> materialGroupIds = priceDiscoveryService.purchaseRequestItems(purchaseRequestId).stream()
            .map(PurchaseRequestItem::getMaterialId)
            .filter(java.util.Objects::nonNull)
            .map(materialId -> materialRepository.findByIdAndIsDeletedFalse(materialId)
                .filter(this::isAccessible)
                .map(material -> material.getMaterialGroupId())
                .orElse(null))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        return materialGroupIds.size() == 1 ? materialGroupIds.iterator().next() : null;
    }
    private String normalizeEnum(String value, String fallback, String[] allowed, String field) {
        String normalized = normalizeToken(value, fallback);
        if (Arrays.asList(allowed).contains(normalized)) {
            return normalized;
        }
        throw new BusinessException("Unsupported " + field + ": " + value);
    }
    private String normalizeToken(String value, String fallback) {
        String token = value == null || value.isBlank() ? fallback : value;
        return token == null ? null : token.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
    private String[] enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    @Transactional(readOnly = true)
    public Page<ProcurementCampaignResponse> search(ProcurementCampaignSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        var spec = ProcurementSearchSpecifications.build(
            ProcurementCampaign.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        return repository.findAll(spec, pageable).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ProcurementCampaignResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ProcurementCampaignResponse restore(UUID id) {
        ProcurementCampaign entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ProcurementCampaign saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ProcurementCampaign findEntity(UUID id) {
        ProcurementCampaign entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }
}

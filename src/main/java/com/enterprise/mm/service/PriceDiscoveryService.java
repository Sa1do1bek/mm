package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.entity.TenderPriceCalculation;
import com.enterprise.mm.entity.TenderPriceCalculationQuotation;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementCampaignStatus;
import com.enterprise.mm.enums.ProcurementMethodDecisionStatus;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.PriceDiscoveryExclusionReason;
import com.enterprise.mm.enums.RfqPurpose;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.RFQMapper;
import com.enterprise.mm.payload.PriceDiscoveryDtos.CreatePriceDiscoveryRfqRequest;
import com.enterprise.mm.payload.PriceDiscoveryDtos.CreateTenderRequest;
import com.enterprise.mm.payload.PriceDiscoveryDtos.CreateTenderResponse;
import com.enterprise.mm.payload.PriceDiscoveryDtos.FinalizePriceDiscoveryRequest;
import com.enterprise.mm.payload.PriceDiscoveryDtos.FinalizePriceDiscoveryResponse;
import com.enterprise.mm.payload.PriceDiscoveryDtos.PriceDiscoveryPreview;
import com.enterprise.mm.payload.PriceDiscoveryDtos.PriceDiscoveryQuotationView;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.TenderPriceCalculationQuotationRepository;
import com.enterprise.mm.repository.TenderPriceCalculationRepository;
import com.enterprise.mm.service.rfq.SupplierPortalEligibilityValidator;
import com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PriceDiscoveryService {
    private static final Set<String> TENDER_METHODS = Set.of(
        ProcurementMethodType.OPEN_TENDER.name(),
        ProcurementMethodType.CLOSED_TENDER.name()
    );

    private final ProcurementCampaignRepository campaignRepository;
    private final ProcurementProcedureRepository procedureRepository;
    private final ProcurementMethodDecisionRepository decisionRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository rfqItemRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final SupplierQuotationRepository quotationRepository;
    private final SupplierQuotationItemRepository quotationItemRepository;
    private final TenderPriceCalculationRepository calculationRepository;
    private final TenderPriceCalculationQuotationRepository calculationQuotationRepository;
    private final SupplierRepository supplierRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final PurchaseRequestTenderStatusService tenderStatusService;
    private final IdempotencyService idempotencyService;
    private final RFQMapper rfqMapper;
    private final AuditService auditService;
    private final SupplierPortalEligibilityValidator supplierPortalEligibilityValidator;
    private final TenderCommissionService tenderCommissionService;
    private final SupplierGovernanceActionPolicy supplierGovernanceActionPolicy;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;

    @Transactional
    public RFQResponse createPriceDiscoveryRfq(UUID campaignId, CreatePriceDiscoveryRfqRequest request) {
        ProcurementCampaign campaign = requireCampaign(campaignId);
        requireCampaignStatus(campaign, ProcurementCampaignStatus.LAUNCHED);
        ProcurementProcedure procedure = requireProcedure(campaign.getProcurementProcedureId());
        requireApprovedDecision(campaign.getMethodDecisionId(), procedure);
        requireTenderMethod(procedure);
        if (procedure.getPriceDiscoveryRfqId() != null) {
            return rfqMapper.toResponse(requireRfq(procedure.getPriceDiscoveryRfqId()));
        }
        requireProcedureStatus(procedure, ProcurementProcedureStatus.LAUNCHED);
        PurchaseRequest purchaseRequest = requireApprovedPurchaseRequest(procedure.getPurchaseRequestId());
        List<PurchaseRequestItem> requestItems = purchaseRequestItems(purchaseRequest.getId());
        if (requestItems.isEmpty()) {
            throw new BusinessException("Purchase request has no lines");
        }
        Set<UUID> invitedSupplierIds = supplierIds(request == null ? List.of() : request.invitedSupplierIds(), false);

        RFQ survey = new RFQ();
        survey.setCode("PD-RFQ-" + shortId());
        survey.setName(BusinessNameGenerator.workflowDocumentName("RFQ", purchaseRequest.getName()));
        survey.setStatus("DRAFT");
        survey.setPurpose(RfqPurpose.PRICE_DISCOVERY.name());
        survey.setProcurementProcedureId(procedure.getId());
        survey.setPurchaseRequestId(purchaseRequest.getId());
        survey.setTenderProcedureType(ProcurementMethodType.REQUEST_FOR_QUOTATION.name());
        survey.setSelectionRule("LOWEST_PRICE");
        survey.setMinQuotationsRequired(2);
        survey.setDueDate(request == null || request.dueDate() == null ? defaultDueDate(campaign, 7) : request.dueDate());
        survey.setCurrencyCode(currency(purchaseRequest.getCurrencyCode()));
        survey.setPackageLanguage(request == null || blank(request.packageLanguage()) == null ? "ru" : request.packageLanguage().trim().toLowerCase(Locale.ROOT));
        String requestedBuyer = request == null ? null : blank(request.responsibleBuyer());
        survey.setResponsibleBuyer(requestedBuyer == null ? currentActor() : requestedBuyer);
        survey.setResponseInstructions(request == null ? null : blank(request.responseInstructions()));
        survey.setCompanyId(CompanyContext.currentCompany());
        RFQ savedSurvey = rfqRepository.save(survey);
        for (PurchaseRequestItem item : requestItems) {
            if (item.getMaterialTypeSnapshot() == null) {
                materialTypeSnapshotService.applySnapshot(item, purchaseRequest.getRequestType());
            }
            copyRfqItem(savedSurvey, item);
        }
        syncInvitations(savedSurvey, invitedSupplierIds);

        procedure.setRequiresPriceDiscovery(true);
        procedure.setPriceDiscoveryRfqId(savedSurvey.getId());
        procedure.setStatus(ProcurementProcedureStatus.PRICE_DISCOVERY.name());
        procedureRepository.save(procedure);
        campaign.setPriceDiscoveryRfqId(savedSurvey.getId());
        campaignRepository.save(campaign);
        auditService.log("RFQ", savedSurvey.getId(), "CREATE_PRICE_DISCOVERY", campaignId.toString(), null, null);
        return rfqMapper.toResponse(savedSurvey);
    }

    @Transactional(readOnly = true)
    public PriceDiscoveryPreview preview(UUID rfqId) {
        RFQ rfq = requireRfq(rfqId);
        requirePriceDiscoveryRfq(rfq);
        List<QuotationAssessment> assessments = assess(rfq);
        List<QuotationAssessment> included = assessments.stream().filter(QuotationAssessment::included).toList();
        BigDecimal amount = average(included);
        return new PriceDiscoveryPreview(
            rfq.getId(),
            rfq.getProcurementProcedureId(),
            amount,
            currency(rfq.getCurrencyCode()),
            included.size(),
            assessments.stream().map(QuotationAssessment::view).toList()
        );
    }

    @Transactional
    public FinalizePriceDiscoveryResponse finalizePriceDiscovery(
        UUID rfqId,
        FinalizePriceDiscoveryRequest request,
        String idempotencyKey
    ) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException("expectedVersion is required for price discovery finalization");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Idempotency-Key is required for price discovery finalization");
        }
        return idempotencyService.execute(
            "PRICE_DISCOVERY_FINALIZE",
            rfqId,
            idempotencyKey,
            request,
            FinalizePriceDiscoveryResponse.class,
            () -> finalizeOnce(rfqId, request.expectedVersion(), idempotencyKey.trim())
        );
    }

    @Transactional
    public CreateTenderResponse createTender(UUID campaignId, CreateTenderRequest request) {
        ProcurementCampaign campaign = requireCampaign(campaignId);
        requireCampaignStatus(campaign, ProcurementCampaignStatus.LAUNCHED);
        ProcurementProcedure procedure = requireProcedure(campaign.getProcurementProcedureId());
        requireTenderMethod(procedure);
        ProcurementMethodDecision decision = requireApprovedDecision(campaign.getMethodDecisionId(), procedure);
        if (campaign.getRfqId() != null) {
            RFQ existing = requireRfq(campaign.getRfqId());
            return tenderResponse(existing, procedure);
        }
        requireProcedureStatus(procedure, ProcurementProcedureStatus.TENDER_READY);
        TenderPriceCalculation calculation = calculationRepository
            .findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), procedure.getId())
            .orElseThrow(() -> new BusinessException("Finalized price discovery is required before tender creation"));
        PurchaseRequest purchaseRequest = requireApprovedOrTenderedPurchaseRequest(procedure.getPurchaseRequestId());
        List<PurchaseRequestItem> requestItems = purchaseRequestItems(purchaseRequest.getId());
        if (requestItems.isEmpty()) {
            throw new BusinessException("Purchase request has no lines");
        }
        Set<UUID> invitedSupplierIds = supplierIds(request == null ? List.of() : request.invitedSupplierIds(), true);

        RFQ tender = new RFQ();
        tender.setCode("TENDER-" + shortId());
        tender.setName(BusinessNameGenerator.workflowDocumentName("RFQ", purchaseRequest.getName()));
        tender.setStatus("DRAFT");
        tender.setPurpose(RfqPurpose.PROCUREMENT.name());
        tender.setProcurementProcedureId(procedure.getId());
        tender.setSourceRfqId(procedure.getPriceDiscoveryRfqId());
        tender.setPriceBasisCalculationId(calculation.getId());
        tender.setPurchaseRequestId(purchaseRequest.getId());
        tender.setTenderProcedureType(procedure.getMethod());
        tender.setSelectionRule("BEST_SCORE");
        tender.setMinQuotationsRequired(Math.max(2, campaign.getTargetSupplierCount() == null ? 2 : campaign.getTargetSupplierCount()));
        tender.setDueDate(request == null || request.dueDate() == null ? defaultDueDate(campaign, 14) : request.dueDate());
        tender.setCurrencyCode(calculation.getCurrencyCode());
        tender.setPackageLanguage(request == null || blank(request.packageLanguage()) == null ? "ru" : request.packageLanguage().trim().toLowerCase(Locale.ROOT));
        String requestedTenderBuyer = request == null ? null : blank(request.responsibleBuyer());
        tender.setResponsibleBuyer(requestedTenderBuyer == null ? currentActor() : requestedTenderBuyer);
        tender.setResponseInstructions(request == null ? null : blank(request.responseInstructions()));
        tender.setCompanyId(CompanyContext.currentCompany());
        RFQ savedTender = rfqRepository.save(tender);
        for (PurchaseRequestItem item : requestItems) {
            if (item.getMaterialTypeSnapshot() == null) {
                materialTypeSnapshotService.applySnapshot(item, purchaseRequest.getRequestType());
            }
            RFQItem rfqItem = copyRfqItem(savedTender, item);
            copySource(savedTender, rfqItem, purchaseRequest, item);
        }
        syncInvitations(savedTender, invitedSupplierIds);
        assignTenderCommission(savedTender.getId(), request == null ? null : request.commissionId(), decision.getCommissionId());
        tenderStatusService.recalculateLocked(List.of(purchaseRequest.getId()));

        procedure.setRfqId(savedTender.getId());
        procedure.setStatus(ProcurementProcedureStatus.TENDER_DRAFT.name());
        procedureRepository.save(procedure);
        campaign.setRfqId(savedTender.getId());
        campaign.setProcedureType(procedure.getMethod());
        campaign.setApprovedSpendAmount(calculation.getAmount());
        campaign.setCurrencyCode(calculation.getCurrencyCode());
        campaign.setStatus("RFQ_CREATED");
        campaignRepository.save(campaign);
        auditService.log("RFQ", savedTender.getId(), "CREATE_TENDER_FROM_PRICE_BASIS", calculation.getId().toString(), null, null);
        return tenderResponse(savedTender, procedure);
    }

    private FinalizePriceDiscoveryResponse finalizeOnce(UUID rfqId, long expectedVersion, String idempotencyKey) {
        RFQ rfq = rfqRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId)
            .orElseThrow(() -> new NotFoundException("RFQ not found: " + rfqId));
        requirePriceDiscoveryRfq(rfq);
        TenderPriceCalculation existing = calculationRepository
            .findByTenantIdAndCompanyIdAndPriceDiscoveryRfqIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfq.getId())
            .orElse(null);
        if (existing != null) {
            return finalizedResponse(existing);
        }
        long actualVersion = rfq.getVersion() == null ? 0L : rfq.getVersion();
        if (actualVersion != expectedVersion) {
            throw new BusinessException("RFQ changed; refresh price discovery preview and try again");
        }
        ProcurementProcedure procedure = requireProcedure(rfq.getProcurementProcedureId());
        requireTenderMethod(procedure);
        requireProcedureStatus(procedure, ProcurementProcedureStatus.PRICE_DISCOVERY);
        List<QuotationAssessment> included = assess(rfq).stream()
            .filter(QuotationAssessment::included)
            .toList();
        if (included.size() < 2) {
            throw new BusinessException("At least two valid quotations are required for price discovery finalization");
        }
        BigDecimal amount = average(included);
        LocalDateTime calculatedAt = LocalDateTime.now();
        TenderPriceCalculation calculation = new TenderPriceCalculation();
        calculation.setCode("TPC-" + shortId());
        calculation.setName(BusinessNameGenerator.workflowDocumentName("TenderPriceCalculation", rfq.getName()));
        calculation.setStatus("FINALIZED");
        calculation.setProcurementProcedureId(procedure.getId());
        calculation.setPriceDiscoveryRfqId(rfq.getId());
        calculation.setAmount(amount);
        calculation.setCurrencyCode(currency(rfq.getCurrencyCode()));
        calculation.setQuotationCount(included.size());
        calculation.setCalculatedBy(currentActor());
        calculation.setCalculatedAt(calculatedAt);
        calculation.setIdempotencyKey(idempotencyKey);
        TenderPriceCalculation saved = calculationRepository.save(calculation);
        for (QuotationAssessment assessment : included) {
            TenderPriceCalculationQuotation evidence = new TenderPriceCalculationQuotation();
            evidence.setCode("TPCQ-" + shortId());
            evidence.setName("Included supplier quotation");
            evidence.setStatus("INCLUDED");
            evidence.setCalculationId(saved.getId());
            evidence.setQuotationId(assessment.quotation().getId());
            evidence.setSupplierId(assessment.quotation().getSupplierId());
            evidence.setTotalAmount(amount(assessment.quotation().getTotalAmount()));
            evidence.setCurrencyCode(currency(assessment.quotation().getCurrencyCode()));
            calculationQuotationRepository.save(evidence);
        }
        rfq.setStatus("CLOSED");
        rfq.setClosedAt(calculatedAt);
        rfqRepository.save(rfq);
        procedure.setFinalizedAmount(saved.getAmount());
        procedure.setFinalizedCurrencyCode(saved.getCurrencyCode());
        procedure.setPriceBasisCalculationId(saved.getId());
        procedure.setStatus(ProcurementProcedureStatus.TENDER_READY.name());
        procedureRepository.save(procedure);
        updateCampaignPriceBasis(procedure, saved);
        auditService.log("TenderPriceCalculation", saved.getId(), "FINALIZE_PRICE_DISCOVERY", rfq.getId().toString(), null, null);
        return finalizedResponse(saved);
    }

    private void updateCampaignPriceBasis(ProcurementProcedure procedure, TenderPriceCalculation calculation) {
        if (procedure.getCampaignId() == null) {
            return;
        }
        campaignRepository.findByIdAndIsDeletedFalse(procedure.getCampaignId()).ifPresent(campaign -> {
            if (scoped(campaign)) {
                campaign.setApprovedSpendAmount(calculation.getAmount());
                campaign.setCurrencyCode(calculation.getCurrencyCode());
                campaign.setPriceBasisCalculationId(calculation.getId());
                campaignRepository.save(campaign);
            }
        });
    }

    private FinalizePriceDiscoveryResponse finalizedResponse(TenderPriceCalculation calculation) {
        List<PriceDiscoveryQuotationView> quotations = calculationQuotationRepository
            .findByTenantIdAndCompanyIdAndCalculationIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), calculation.getId())
            .stream()
            .map(line -> new PriceDiscoveryQuotationView(
                line.getQuotationId(),
                line.getSupplierId(),
                line.getTotalAmount(),
                line.getCurrencyCode(),
                true,
                null))
            .toList();
        return new FinalizePriceDiscoveryResponse(
            calculation.getId(),
            calculation.getPriceDiscoveryRfqId(),
            calculation.getProcurementProcedureId(),
            calculation.getAmount(),
            calculation.getCurrencyCode(),
            calculation.getQuotationCount() == null ? quotations.size() : calculation.getQuotationCount(),
            calculation.getCalculatedAt(),
            quotations
        );
    }

    private void assignTenderCommission(UUID rfqId, UUID requestCommissionId, UUID decisionCommissionId) {
        UUID commissionId = requestCommissionId == null ? decisionCommissionId : requestCommissionId;
        if (commissionId != null) {
            tenderCommissionService.assignProcessCommission(rfqId, commissionId);
        }
    }

    private List<QuotationAssessment> assess(RFQ rfq) {
        String currency = currency(rfq.getCurrencyCode());
        Set<UUID> rfqItemIds = rfqItems(rfq.getId()).stream().map(RFQItem::getId).collect(Collectors.toSet());
        List<SupplierQuotation> quotations = quotationRepository
            .findByTenantIdAndCompanyIdAndRfqIdOrderBySupplierIdAscRevisionNumberAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfq.getId())
            .stream()
            .sorted(Comparator.comparing(SupplierQuotation::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        Set<UUID> latestLegacyQuotationIds = latestLegacyQuotationIds(quotations);
        List<QuotationAssessment> assessments = new ArrayList<>();
        for (SupplierQuotation quotation : quotations) {
            String reason = exclusionReason(quotation, rfqItemIds, currency, latestLegacyQuotationIds);
            assessments.add(new QuotationAssessment(quotation, reason == null, reason));
        }
        return assessments;
    }

    private Set<UUID> latestLegacyQuotationIds(List<SupplierQuotation> quotations) {
        Map<UUID, SupplierQuotation> latestBySupplier = quotations.stream()
            .filter(quotation -> quotation.getThreadId() == null)
            .filter(quotation -> !quotation.isDeleted())
            .collect(Collectors.toMap(
                SupplierQuotation::getSupplierId,
                Function.identity(),
                (left, right) -> compareQuotationRecency(left, right) >= 0 ? left : right
            ));
        return latestBySupplier.values().stream()
            .map(SupplierQuotation::getId)
            .collect(Collectors.toSet());
    }

    private int compareQuotationRecency(SupplierQuotation left, SupplierQuotation right) {
        Comparator<SupplierQuotation> comparator = Comparator
            .comparing(SupplierQuotation::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(SupplierQuotation::getId, Comparator.nullsFirst(Comparator.naturalOrder()));
        return comparator.compare(left, right);
    }

    private String exclusionReason(SupplierQuotation quotation, Set<UUID> rfqItemIds, String currency, Set<UUID> latestLegacyQuotationIds) {
        if (quotation.isDeleted()) {
            return PriceDiscoveryExclusionReason.DELETED.name();
        }
        if (quotation.getThreadId() != null && !quotation.isCurrentRevision()) {
            return PriceDiscoveryExclusionReason.NOT_CURRENT.name();
        }
        if (quotation.getThreadId() == null && !latestLegacyQuotationIds.contains(quotation.getId())) {
            return PriceDiscoveryExclusionReason.NOT_CURRENT.name();
        }
        if (!"SUBMITTED".equals(quotation.getStatus())) {
            return PriceDiscoveryExclusionReason.NOT_SUBMITTED.name();
        }
        if (!currency.equals(currency(quotation.getCurrencyCode()))) {
            return PriceDiscoveryExclusionReason.WRONG_CURRENCY.name();
        }
        if (amount(quotation.getTotalAmount()).signum() <= 0) {
            return PriceDiscoveryExclusionReason.NON_POSITIVE_TOTAL.name();
        }
        List<SupplierQuotationItem> lines = quotationItemRepository
            .findByTenantIdAndCompanyIdAndQuotationIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), quotation.getId());
        Set<UUID> lineIds = lines.stream().map(SupplierQuotationItem::getRfqItemId).collect(Collectors.toSet());
        if (lines.size() != rfqItemIds.size() || !lineIds.equals(rfqItemIds)) {
            return PriceDiscoveryExclusionReason.INCOMPLETE_LINES.name();
        }
        if (lines.stream().anyMatch(SupplierQuotationItem::isNoBid)) {
            return PriceDiscoveryExclusionReason.NO_BID_LINE.name();
        }
        if (lines.stream().anyMatch(line -> amount(line.getQuantity()).signum() <= 0 || amount(line.getUnitPrice()).signum() <= 0)) {
            return PriceDiscoveryExclusionReason.NON_POSITIVE_TOTAL.name();
        }
        return null;
    }

    RFQItem copyRfqItem(RFQ rfq, PurchaseRequestItem item) {
        RFQItem rfqItem = new RFQItem();
        rfqItem.setCode("RFQI-" + shortId());
        rfqItem.setName(firstText(item.getName(), "RFQ item"));
        rfqItem.setStatus("OPEN");
        rfqItem.setCompanyId(CompanyContext.currentCompany());
        rfqItem.setRfqId(rfq.getId());
        rfqItem.setLineType(item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType());
        rfqItem.setMaterialId(item.getMaterialId());
        rfqItem.setMaterialTypeSnapshot(item.getMaterialTypeSnapshot());
        rfqItem.setServiceRequestId(item.getServiceRequestId());
        rfqItem.setServiceDescription(item.getServiceDescription());
        rfqItem.setServiceCategory(item.getServiceCategory());
        rfqItem.setUnit(unitFromSource(item));
        rfqItem.setPeriod(item.getPeriod());
        rfqItem.setStage(item.getStage());
        rfqItem.setBudgetItemId(item.getBudgetItemId());
        rfqItem.setQuantity(item.getQuantity());
        return rfqItemRepository.save(rfqItem);
    }

    private void copySource(RFQ rfq, RFQItem rfqItem, PurchaseRequest parent, PurchaseRequestItem item) {
        if (sourceRepository.existsByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), item.getId(), RfqSourceAllocationStatus.ACTIVE.name())) {
            throw new BusinessException("PR item is already allocated to an RFQ", "rfq.aggregation.already_allocated", item.getId());
        }
        RFQPurchaseRequestSource source = new RFQPurchaseRequestSource();
        source.setCode("RFQSRC-" + shortId());
        source.setName(parent.getName() + " allocation");
        source.setStatus(RfqSourceAllocationStatus.ACTIVE.name());
        source.setCompanyId(CompanyContext.currentCompany());
        source.setRfqId(rfq.getId());
        source.setRfqItemId(rfqItem.getId());
        source.setPurchaseRequestId(parent.getId());
        source.setPurchaseRequestItemId(item.getId());
        source.setAllocatedQuantity(item.getQuantity());
        source.setSourceRequiredDate(item.fulfillmentDate(parent));
        source.setSourceDepartmentId(parent.getDepartmentId());
        source.setSourceBudgetItemId(item.getBudgetItemId());
        source.setSourceCurrencyCode(parent.getCurrencyCode());
        source.setSourceUnit(unitFromSource(item));
        source.setAllocatedAt(LocalDateTime.now());
        sourceRepository.save(source);
    }

    void syncInvitations(RFQ rfq, Set<UUID> supplierIds) {
        for (UUID supplierId : supplierIds) {
            RfqSupplierInvitation invitation = new RfqSupplierInvitation();
            invitation.setCode("RFQ-INV-" + shortId());
            invitation.setName(BusinessNameGenerator.workflowDocumentName("RfqSupplierInvitation", rfq.getName()));
            invitation.setStatus("INVITED");
            invitation.setCompanyId(CompanyContext.currentCompany());
            invitation.setRfqId(rfq.getId());
            invitation.setSupplierId(supplierId);
            invitationRepository.save(invitation);
        }
    }

    Set<UUID> supplierIds(List<UUID> requestedSupplierIds, boolean tenderLike) {
        if (requestedSupplierIds == null || requestedSupplierIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(requestedSupplierIds);
        if (ids.contains(null)) {
            throw new BusinessException("RFQ invitations require an active supplier in the current tenant and company");
        }
        Map<UUID, Supplier> suppliers = supplierRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(Supplier::getId, Function.identity()));
        boolean valid = suppliers.size() == ids.size() && ids.stream().allMatch(id -> {
            Supplier supplier = suppliers.get(id);
            return supplier != null
                && !supplier.isDeleted()
                && TenantContext.currentTenant().equals(supplier.getTenantId())
                && CompanyContext.currentCompany().equals(supplier.getCompanyId())
                && List.of("ACTIVE", "LIMITED").contains(supplier.getStatus());
        });
        if (!valid) {
            throw new BusinessException("RFQ invitations require an active supplier in the current tenant and company");
        }
        requireSupplierPortalEligibility(ids, suppliers, tenderLike);
        return ids;
    }

    boolean isTenderMethod(String method) {
        return method != null && TENDER_METHODS.contains(method);
    }

    private void requireSupplierPortalEligibility(Set<UUID> supplierIds, Map<UUID, Supplier> suppliers, boolean tenderLike) {
        supplierGovernanceActionPolicy.requireSupplierActionAllowed(tenderLike ? "ADD_TO_TENDER" : "ADD_TO_RFQ");
        for (UUID supplierId : supplierIds) {
            Supplier supplier = suppliers.get(supplierId);
            List<SupplierPortalEligibilityValidator.EligibilityIssue> issues =
                supplierPortalEligibilityValidator.validate(supplier);
            if (!issues.isEmpty()) {
                throw new BusinessException(
                    "RFQ invitations require supplier portal email/account eligibility: supplierId="
                        + supplierId + ", issue=" + issues.get(0).code()
                );
            }
        }
    }

    private ProcurementCampaign requireCampaign(UUID id) {
        return campaignRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new NotFoundException("ProcurementCampaign not found: " + id));
    }

    private ProcurementProcedure requireProcedure(UUID id) {
        if (id == null) {
            throw new BusinessException("procurementProcedureId is required");
        }
        return procedureRepository.lockScopedById(TenantContext.currentTenant(), CompanyContext.currentCompany(), id)
            .orElseThrow(() -> new BusinessException("Procurement procedure not found: " + id));
    }

    private ProcurementMethodDecision requireApprovedDecision(UUID decisionId, ProcurementProcedure procedure) {
        if (decisionId == null) {
            throw new BusinessException("methodDecisionId is required");
        }
        ProcurementMethodDecision decision = decisionRepository.findByIdAndIsDeletedFalse(decisionId)
            .filter(this::scoped)
            .orElseThrow(() -> new BusinessException("Procurement method decision not found: " + decisionId));
        if (!ProcurementMethodDecisionStatus.APPROVED.name().equals(decision.getStatus())) {
            throw new BusinessException("Approved procurement method decision is required");
        }
        if (!decision.getId().equals(procedure.getMethodDecisionId())) {
            throw new BusinessException("Procurement procedure does not belong to method decision");
        }
        return decision;
    }

    PurchaseRequest requireApprovedPurchaseRequest(UUID id) {
        PurchaseRequest request = requirePurchaseRequest(id);
        if (!PurchaseRequestStatus.APPROVED.name().equals(request.getStatus())) {
            throw new BusinessException("Purchase request must be APPROVED before price discovery");
        }
        return request;
    }

    private PurchaseRequest requireApprovedOrTenderedPurchaseRequest(UUID id) {
        PurchaseRequest request = requirePurchaseRequest(id);
        if (!Set.of(
            PurchaseRequestStatus.APPROVED.name(),
            PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
            PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name(),
            PurchaseRequestStatus.RFQ_CREATED.name()
        ).contains(request.getStatus())) {
            throw new BusinessException("Purchase request must be approved or in tender allocation");
        }
        return request;
    }

    private PurchaseRequest requirePurchaseRequest(UUID id) {
        if (id == null) {
            throw new BusinessException("purchaseRequestId is required");
        }
        return purchaseRequestRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new BusinessException("Purchase request not found: " + id));
    }

    private RFQ requireRfq(UUID id) {
        return rfqRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::scoped)
            .orElseThrow(() -> new NotFoundException("RFQ not found: " + id));
    }

    private void requirePriceDiscoveryRfq(RFQ rfq) {
        if (!RfqPurpose.PRICE_DISCOVERY.name().equals(rfq.getPurpose())) {
            throw new BusinessException("RFQ is not a price discovery survey");
        }
    }

    private void requireTenderMethod(ProcurementProcedure procedure) {
        if (procedure == null || !TENDER_METHODS.contains(procedure.getMethod())) {
            throw new BusinessException("Price discovery is required only for OPEN_TENDER and CLOSED_TENDER");
        }
    }

    private void requireCampaignStatus(ProcurementCampaign campaign, ProcurementCampaignStatus expected) {
        if (campaign == null || !expected.name().equals(campaign.getStatus())) {
            throw new BusinessException("Procurement campaign must be " + expected.name() + " for this action");
        }
    }

    private void requireProcedureStatus(ProcurementProcedure procedure, ProcurementProcedureStatus expected) {
        if (procedure == null || !expected.name().equals(procedure.getStatus())) {
            throw new BusinessException("Procurement procedure must be " + expected.name() + " for this action");
        }
    }

    List<PurchaseRequestItem> purchaseRequestItems(UUID purchaseRequestId) {
        return purchaseRequestItemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseRequestId)
            .stream()
            .filter(this::scoped)
            .toList();
    }

    private List<RFQItem> rfqItems(UUID rfqId) {
        return rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfqId)
            .stream()
            .filter(this::scoped)
            .toList();
    }

    private BigDecimal average(List<QuotationAssessment> assessments) {
        if (assessments == null || assessments.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal sum = assessments.stream()
            .map(assessment -> amount(assessment.quotation().getTotalAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(assessments.size()), 4, RoundingMode.HALF_UP);
    }

    private CreateTenderResponse tenderResponse(RFQ tender, ProcurementProcedure procedure) {
        return new CreateTenderResponse(
            tender.getId(),
            procedure.getId(),
            procedure.getPriceDiscoveryRfqId(),
            procedure.getPriceBasisCalculationId(),
            procedure.getFinalizedAmount(),
            procedure.getFinalizedCurrencyCode(),
            tender.getStatus()
        );
    }

    LocalDate defaultDueDate(ProcurementCampaign campaign, int days) {
        return campaign.getPlannedEndDate() == null ? LocalDate.now().plusDays(days) : campaign.getPlannedEndDate();
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }

    String currency(String value) {
        String normalized = value == null || value.isBlank() ? "UZS" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new BusinessException("Currency code must contain three letters");
        }
        return normalized;
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String unitFromSource(PurchaseRequestItem item) {
        if (item == null) {
            return null;
        }
        String unit = normalize(item.getUnit());
        if (unit != null) {
            return unit;
        }
        var material = item.getMaterial();
        if (material == null || material.getUnitOfMeasure() == null) {
            return null;
        }
        String unitFromMaterial = normalize(material.getUnitOfMeasure().getMeasurementNameShort());
        if (unitFromMaterial != null) {
            return unitFromMaterial;
        }
        return normalize(material.getUnitOfMeasure().getMeasurementNameLong());
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "system" : authentication.getName();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private record QuotationAssessment(SupplierQuotation quotation, boolean included, String exclusionReason) {
        PriceDiscoveryQuotationView view() {
            return new PriceDiscoveryQuotationView(
                quotation.getId(),
                quotation.getSupplierId(),
                quotation.getTotalAmount(),
                quotation.getCurrencyCode(),
                included,
                exclusionReason
            );
        }
    }
}

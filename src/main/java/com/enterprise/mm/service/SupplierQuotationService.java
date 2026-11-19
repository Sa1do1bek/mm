package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SupplierQuotationResponse;
import com.enterprise.mm.payload.SupplierQuotationUpdateRequest;
import com.enterprise.mm.payload.SupplierQuotationCreateRequest;
import com.enterprise.mm.payload.SupplierQuotationItemCreateRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.mapper.SupplierQuotationMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.enterprise.mm.payload.SupplierQuotationSearchRequest;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class SupplierQuotationService {
    private final SupplierQuotationRepository repository;
    private final SupplierQuotationMapper mapper;
    private final String entityName = "SupplierQuotation";
    private final AuditService auditService;
    private final SupplierQuotationItemRepository quotationItemRepository;
    private final SupplierQuotationItemService quotationItemService;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository rfqItemRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final IntegrationEventService integrationEventService;
    private final TenderProcessService tenderProcessService;
    private final RoleDataScopeService roleDataScopeService;
    private final QuotationSelectionValidationService quotationSelectionValidationService;
    private final SupplierAccreditationService supplierAccreditationService;
    private final SupplierEligibilityService supplierEligibilityService;
    private final MaterialRepository materialRepository;
    private final ProcurementAwardFinalizationService awardFinalizationService;
    private final PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    @org.springframework.beans.factory.annotation.Autowired
    private ProcurementDocumentCategoryValidator documentCategoryValidator = new ProcurementDocumentCategoryValidator();

    public SupplierQuotationService(
        SupplierQuotationRepository repository,
        SupplierQuotationMapper mapper,
        AuditService auditService,
        SupplierQuotationItemRepository quotationItemRepository,
        SupplierQuotationItemService quotationItemService,
        RFQRepository rfqRepository,
        RFQItemRepository rfqItemRepository,
        SupplierRepository supplierRepository,
        PurchaseOrderRepository purchaseOrderRepository,
        PurchaseOrderItemRepository purchaseOrderItemRepository,
        PurchaseOrderMapper purchaseOrderMapper,
        IntegrationEventService integrationEventService,
        TenderProcessService tenderProcessService,
        RoleDataScopeService roleDataScopeService,
        QuotationSelectionValidationService quotationSelectionValidationService,
        SupplierAccreditationService supplierAccreditationService,
        MaterialRepository materialRepository,
        ProcurementAwardFinalizationService awardFinalizationService,
        PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy
    ) {
        this(repository, mapper, auditService, quotationItemRepository, quotationItemService, rfqRepository,
            rfqItemRepository, supplierRepository, purchaseOrderRepository, purchaseOrderItemRepository,
            purchaseOrderMapper, integrationEventService, tenderProcessService, roleDataScopeService,
            quotationSelectionValidationService, supplierAccreditationService, null, materialRepository,
            awardFinalizationService, purchaseOrderDemandCyclePolicy);
    }
    protected void beforeCreate(SupplierQuotation entity, SupplierQuotationCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (!applyWorkflowNameFromRfq(entity)) {
            entity.setName(BusinessDisplayNamePolicy.requireHumanName(
                entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
            ));
        }

        recalculateScore(entity);
        normalizeAndValidateScores(entity);
    }
    protected void beforeUpdate(SupplierQuotation entity, SupplierQuotationUpdateRequest dto) {
        if (!applyWorkflowNameFromRfq(entity)) {
            entity.setName(BusinessDisplayNamePolicy.requireHumanName(
                entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
            ));
        }

        recalculateScore(entity);
        normalizeAndValidateScores(entity);
    }

    private boolean applyWorkflowNameFromRfq(SupplierQuotation entity) {
        UUID rfqId = entity.getRfqId();
        if (rfqId == null && entity.getRfq() != null) {
            rfqId = entity.getRfq().getId();
        }
        if (rfqId == null) {
            return false;
        }
        UUID lookupRfqId = rfqId;
        RFQ rfq = rfqRepository.findByIdAndIsDeletedFalse(lookupRfqId)
            .filter(candidate -> TenantContext.currentTenant().equals(candidate.getTenantId()))
            .filter(candidate -> CompanyContext.currentCompany().equals(candidate.getCompanyId()))
            .orElseThrow(() -> new BusinessException("RFQ not found: " + lookupRfqId));
        entity.setName(BusinessNameGenerator.workflowDocumentName("SupplierQuotation", rfq.getName()));
        return true;
    }
    @Transactional(readOnly = true)
    public List<SupplierQuotationResponse> compare(UUID rfqId) {
        return currentSubmitted(rfqId).stream()
            .filter(quotation -> com.enterprise.mm.security.AccessScope.canAccessCompany(quotation.getCompanyId()))
            .map(this::toResponseWithSelectionReadiness)
            .toList();
    }
    @Transactional
    public PurchaseOrderResponse selectAndCreatePo(UUID quotationId, String reason) {
        SupplierQuotation selected = repository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), quotationId)
            .filter(quotation -> com.enterprise.mm.security.AccessScope.canAccessCompany(quotation.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Quotation not found"));
        requireCurrentSubmittedRevision(selected);
        RFQ rfq = rfqRepository.findByIdAndIsDeletedFalse(selected.getRfqId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("RFQ not found"));
        purchaseOrderDemandCyclePolicy.requireApprovedCycleForPurchaseRequest(rfq.getPurchaseRequestId());
        if (rfq.isAggregatedTender()) {
            throw new BusinessException("Aggregated RFQ must be awarded to a contract, not converted to PO");
        }
        Supplier supplier = supplierRepository.findByIdAndIsDeletedFalse(selected.getSupplierId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Supplier not found"));
        if ("BLOCKED".equals(supplier.getStatus())) {
            throw new BusinessException("Blocked supplier cannot be selected");
        }
        List<SupplierQuotation> quotations = currentSubmitted(rfq.getId()).stream()
            .filter(quotation -> com.enterprise.mm.security.AccessScope.canAccessCompany(quotation.getCompanyId()))
            .toList();
        quotationSelectionValidationService.validateSelection(selected, rfq, quotations, reason);
        List<SupplierQuotationItem> quoteItems = quotationItemRepository.findByTenantIdAndQuotation_IdAndIsDeletedFalse(TenantContext.currentTenant(), selected.getId()).stream()
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .toList();
        if (quoteItems.isEmpty()) {
            throw new BusinessException("Selected quotation has no items");
        }
        validateExactRfqCoverage(rfq, quoteItems);
        RequestType requestType = resolveRequestType(rfq);
        if (requestType == RequestType.GOODS) {
            documentCategoryValidator.validateNoMixedGoodsBusinessTypes(
                "purchaseOrder",
                quoteItems.stream().map(this::materialTypeSnapshot).toList()
            );
        }
        List<SupplierAccreditationDemandResolver.DemandLine> demandLines = quoteItems.stream()
            .map(item -> new SupplierAccreditationDemandResolver.DemandLine(
                resolvePoLineType(requestType, item),
                item.getMaterialId()))
            .toList();
        requireNewBusinessAllowed(
            supplier.getId(),
            SupplierAccreditationDemandResolver.resolve(
                demandLines,
                selected.getCompanyId(),
                materialRepository),
            "RFQ_AWARD"
        );
        quotations.forEach(quotation -> {
            quotation.setSelected(quotation.getId().equals(selected.getId()));
            quotation.setStatus(quotation.getId().equals(selected.getId()) ? "SELECTED" : "REJECTED");
            if (quotation.getId().equals(selected.getId())) {
                quotation.setSelectionReason(reason);
            }
            repository.save(quotation);
        });
        PurchaseOrder po = new PurchaseOrder();
        po.setCode("PO-" + selected.getCode());
        po.setName(BusinessNameGenerator.workflowDocumentName("PurchaseOrder", rfq.getName()));
        po.setStatus(PurchaseOrderStatus.DRAFT.name());
        po.setSupplierId(supplier.getId());
        po.setPurchaseRequestId(rfq.getPurchaseRequestId());
        po.setRequestType(requestType);
        po.setOrderDate(LocalDate.now());
        po.setExpectedDeliveryDate(expectedDeliveryDate(quoteItems));
        po.setTotalAmount(amount(selected.getTotalAmount()));
        po.setCurrencyCode(selected.getCurrencyCode());
        po = purchaseOrderRepository.save(po);
        for (SupplierQuotationItem quoteItem : quoteItems) {
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setCode("POI-" + quoteItem.getCode());
            item.setName("PO item " + quoteItem.getName());
            item.setStatus("ACTIVE");
            item.setPurchaseOrderId(po.getId());
            item.setLineType(resolvePoLineType(requestType, quoteItem));
            item.setMaterialId(item.getLineType() == ProcurementLineType.MATERIAL ? quoteItem.getMaterialId() : null);
            item.setMaterialTypeSnapshot(materialTypeSnapshot(quoteItem));
            item.setServiceRequestId(quoteItem.getServiceRequestId());
            item.setServiceDescription(item.getLineType() == ProcurementLineType.SERVICE
                ? firstText(quoteItem.getServiceDescription(), quoteItem.getName())
                : quoteItem.getServiceDescription());
            item.setServiceCategory(quoteItem.getServiceCategory());
            item.setUnit(quoteItem.getUnit());
            item.setPeriod(quoteItem.getPeriod());
            item.setStage(quoteItem.getStage());
            item.setBudgetItemId(quoteItem.getBudgetItemId());
            item.setQuantity(amount(quoteItem.getQuantity()));
            item.setUnitPrice(amount(quoteItem.getUnitPrice()));
            item.setReceivedQuantity(BigDecimal.ZERO);
            if (item.getLineType() == ProcurementLineType.MATERIAL)
                StockUomPolicy.purchasing(materialRepository.findByIdAndIsDeletedFalse(item.getMaterialId()).orElseThrow(() -> new BusinessException("Material not found")), item.getUnit(), item.getQuantity());
            PurchaseOrderItem savedItem = purchaseOrderItemRepository.save(item);
            awardFinalizationService.recordLineage(quoteItem.getRfqItemId(), savedItem);
        }
        awardFinalizationService.completeCompetitiveAward(rfq, selected, po);
        integrationEventService.emit("RFQ_QUOTATION_SELECTED", "SupplierQuotation", selected.getId(), Map.of(
            "rfqId", rfq.getId(),
            "quotationId", selected.getId(),
            "purchaseOrderId", po.getId(),
            "supplierId", selected.getSupplierId()
        ));
        auditService.log("SupplierQuotation", selected.getId(), "SELECT_AND_CREATE_PO", po.getCode(), null, null);
        return purchaseOrderMapper.toResponse(po);
    }

    private void validateExactRfqCoverage(RFQ rfq, List<SupplierQuotationItem> quoteItems) {
        List<RFQItem> rfqItems = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), rfq.getId()
        ).stream()
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .toList();
        if (rfqItems.isEmpty()) {
            throw new BusinessException("RFQ has no items");
        }
        Map<UUID, RFQItem> expected = rfqItems.stream().collect(java.util.stream.Collectors.toMap(RFQItem::getId, item -> item));
        HashSet<UUID> seen = new HashSet<>();
        for (SupplierQuotationItem quoteItem : quoteItems) {
            RFQItem rfqItem = expected.get(quoteItem.getRfqItemId());
            if (rfqItem == null) {
                throw new BusinessException("Selected quotation contains an unknown RFQ item");
            }
            if (!seen.add(rfqItem.getId())) {
                throw new BusinessException("Selected quotation contains a duplicate RFQ item");
            }
            if (!Objects.equals(normalizeUnit(rfqItem.getUnit()), normalizeUnit(quoteItem.getUnit()))
                || amount(rfqItem.getQuantity()).compareTo(amount(quoteItem.getQuantity())) != 0) {
                throw new BusinessException("Selected quotation must match RFQ item quantity and unit exactly");
            }
        }
        if (seen.size() != expected.size()) {
            throw new BusinessException("Selected quotation must cover every RFQ item exactly once");
        }
    }

    private String normalizeUnit(String unit) {
        return unit == null ? null : unit.trim().toUpperCase(Locale.ROOT);
    }

    private MaterialType materialTypeSnapshot(SupplierQuotationItem item) {
        if (item.getMaterialTypeSnapshot() != null) {
            return item.getMaterialTypeSnapshot();
        }
        if (item.getMaterial() != null && item.getMaterial().getMaterialType() != null) {
            return item.getMaterial().getMaterialType();
        }
        if (item.getMaterialId() != null) {
            return materialRepository.findByIdAndIsDeletedFalse(item.getMaterialId())
                .map(Material::getMaterialType)
                .orElse(MaterialType.TMB);
        }
        return MaterialType.SERVICES;
    }

    private void requireNewBusinessAllowed(UUID supplierId, com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand demand, String operation) {
        if (supplierEligibilityService != null) {
            supplierEligibilityService.requireNewBusinessAllowed(supplierId, demand, operation);
            return;
        }
        supplierAccreditationService.assertSupplierEligibleForDemand(supplierId, demand, "supplier quotation award");
    }

    private ProcurementLineType resolvePoLineType(RequestType requestType, SupplierQuotationItem item) {
        if (item != null && item.getLineType() == ProcurementLineType.WORK) {
            return ProcurementLineType.WORK;
        }
        if (requestType == RequestType.SERVICE) {
            return ProcurementLineType.SERVICE;
        }
        return item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
    }


    private String firstText(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private void validateTenderSelection(RFQ rfq, SupplierQuotation selected, List<SupplierQuotation> quotations) {
        String procedure = rfq.getTenderProcedureType() == null ? "REQUEST_FOR_QUOTATION" : rfq.getTenderProcedureType();
        String rule = rfq.getSelectionRule() == null ? "LOWEST_PRICE" : rfq.getSelectionRule();
        int minRequired = rfq.getMinQuotationsRequired() == null ? ("SINGLE_SUPPLIER".equals(procedure) ? 1 : 2) : rfq.getMinQuotationsRequired();
        if (quotations.size() < minRequired) {
            throw new BusinessException("Tender procedure requires at least " + minRequired + " quotations");
        }
        if ("SINGLE_SUPPLIER".equals(procedure) || "SINGLE_SUPPLIER".equals(rule)) {
            return;
        }
        if ("LOWEST_PRICE".equals(rule)) {
            BigDecimal lowest = quotations.stream()
                .map(quotation -> amount(quotation.getTotalAmount()))
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
            if (amount(selected.getTotalAmount()).compareTo(lowest) != 0) {
                throw new BusinessException("Selection rule LOWEST_PRICE requires the lowest price quotation");
            }
        }
        if ("BEST_SCORE".equals(rule)) {
            BigDecimal best = quotations.stream()
                .map(quotation -> amount(quotation.getTotalScore()))
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
            if (amount(selected.getTotalScore()).compareTo(best) != 0) {
                throw new BusinessException("Selection rule BEST_SCORE requires the highest scored quotation");
            }
        }
    }
    private void recalculateScore(SupplierQuotation quotation) {
        if (quotation.getTotalScore() != null && quotation.getTotalScore().signum() > 0) {
            return;
        }
        quotation.setTotalScore(amount(quotation.getTechnicalScore()).add(amount(quotation.getDeliveryScore())).add(amount(quotation.getPriceScore())));
    }

    private void normalizeAndValidateScores(SupplierQuotation quotation) {
        quotation.setTechnicalScore(score(quotation.getTechnicalScore(), "technicalScore", BigDecimal.valueOf(100)));
        quotation.setDeliveryScore(score(quotation.getDeliveryScore(), "deliveryScore", BigDecimal.valueOf(100)));
        quotation.setPriceScore(score(quotation.getPriceScore(), "priceScore", BigDecimal.valueOf(100)));
        quotation.setTotalScore(score(quotation.getTotalScore(), "totalScore", BigDecimal.valueOf(300)));
    }

    private BigDecimal score(BigDecimal value, String field, BigDecimal max) {
        BigDecimal normalized = amount(value).setScale(2, RoundingMode.HALF_UP);
        if (normalized.signum() < 0 || normalized.compareTo(max) > 0) {
            throw new BusinessException(field + " must be between 0 and " + max.stripTrailingZeros().toPlainString());
        }
        return normalized;
    }

    private LocalDate expectedDeliveryDate(List<SupplierQuotationItem> items) {
        int days = items.stream().map(SupplierQuotationItem::getDeliveryDays).filter(value -> value != null && value > 0).max(Integer::compareTo).orElse(0);
        return LocalDate.now().plusDays(days);
    }
    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private RequestType resolveRequestType(RFQ rfq) {
        if (rfq.getPurchaseRequest() != null && rfq.getPurchaseRequest().getRequestType() != null) {
            return rfq.getPurchaseRequest().getRequestType();
        }
        return RequestType.GOODS;
    }
    @Transactional(readOnly = true)
    public Page<SupplierQuotationResponse> search(SupplierQuotationSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        RoleDataScopeService.DataScope dataScope = roleDataScopeService.currentScope();
        var pageRequest = dataScope.hasDocumentScope() ? Pageable.unpaged() : pageable;
        var spec = ProcurementSearchSpecifications.build(
            SupplierQuotation.class,
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter,
            request
        );
        var records = repository.findAll(spec, pageRequest);
        if (!dataScope.hasDocumentScope()) {
            return records.map(this::toResponseWithSelectionReadiness);
        }
        return roleDataScopeService.filterAndMap(
            records.getContent(),
            pageable,
            entity -> entity.getRfq() != null
                && entity.getRfq().getPurchaseRequest() != null
                && roleDataScopeService.canAccessDocument(
                    entity.getRfq().getPurchaseRequest().getCreatedBy(),
                    entity.getRfq().getPurchaseRequest().getRequestedBy(),
                    entity.getRfq().getPurchaseRequest().getDepartmentId()
                ),
            this::toResponseWithSelectionReadiness
        );
    }
    @Transactional(readOnly = true)
    public SupplierQuotationResponse get(UUID id) {
        return toResponseWithSelectionReadiness(findEntity(id));
    }
    @Transactional
    public SupplierQuotationResponse create(SupplierQuotationCreateRequest dto) {
        SupplierQuotation entity = mapper.toEntity(dto);
        validateNoDuplicateRfqLines(dto.getItems());
        recalculateTotals(entity, dto);
        beforeCreate(entity, dto);
        SupplierQuotation saved = repository.save(entity);
        saveSubmittedItems(saved.getId(), dto.getItems(), false);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponseWithSelectionReadiness(saved);
    }
    @Transactional
    public SupplierQuotationResponse update(UUID id, SupplierQuotationUpdateRequest dto) {
        SupplierQuotation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireLegacyMutable(entity);
        if ("SELECTED".equals(entity.getStatus()) || "REJECTED".equals(entity.getStatus())) {
            throw new BusinessException("Selected or rejected quotation cannot be edited");
        }
        validateNoDuplicateRfqLines(dto.getItems());
        String previousStatus = entity.getStatus();
        boolean previousSelected = entity.isSelected();
        LocalDateTime previousSubmittedAt = entity.getSubmittedAt();
        String previousSelectionReason = entity.getSelectionReason();
        mapper.updateEntity(entity, dto);
        entity.setStatus(previousStatus);
        entity.setSelected(previousSelected);
        entity.setSubmittedAt(previousSubmittedAt);
        entity.setSelectionReason(previousSelectionReason);
        recalculateTotals(entity, dto);
        beforeUpdate(entity, dto);
        SupplierQuotation saved = repository.save(entity);
        saveSubmittedItems(saved.getId(), dto.getItems(), true);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponseWithSelectionReadiness(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        SupplierQuotation entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        requireLegacyMutable(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public SupplierQuotationResponse restore(UUID id) {
        SupplierQuotation entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SupplierQuotation saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return toResponseWithSelectionReadiness(saved);
    }
    protected SupplierQuotation findEntity(UUID id) {
        SupplierQuotation entity = repository.findByIdAndIsDeletedFalse(id)
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
        if (entity.getRfq() != null
            && entity.getRfq().getPurchaseRequest() != null
            && !roleDataScopeService.canAccessDocument(
                entity.getRfq().getPurchaseRequest().getCreatedBy(),
                entity.getRfq().getPurchaseRequest().getRequestedBy(),
                entity.getRfq().getPurchaseRequest().getDepartmentId()
            )) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }

    private SupplierQuotationResponse toResponseWithSelectionReadiness(SupplierQuotation quotation) {
        SupplierQuotationResponse response = mapper.toResponse(quotation);
        if (response == null) {
            return null;
        }
        SelectionReadiness readiness = selectionReadiness(quotation);
        response.setSelectCreatePoReady(readiness.poReady());
        response.setSelectCreatePoBlockedReason(readiness.poBlockedReason());
        response.setAwardContractReady(readiness.awardReady());
        response.setAwardContractBlockedReason(readiness.awardBlockedReason());
        return response;
    }

    private SelectionReadiness selectionReadiness(SupplierQuotation quotation) {
        if (quotation == null || quotation.getId() == null) {
            return SelectionReadiness.notReady("Quotation is not available");
        }
        if (quotation.getThreadId() != null && !quotation.isCurrentRevision()) {
            return SelectionReadiness.notReady("Superseded quotation revision cannot be selected");
        }
        String status = quotation.getStatus();
        if (!("SUBMITTED".equals(status) || "ACTIVE".equals(status))) {
            return SelectionReadiness.notReady("Quotation must be submitted before PO creation");
        }
        if (quotation.getRfqId() == null) {
            return SelectionReadiness.notReady("RFQ is required before PO creation");
        }
        RFQ rfq = rfqRepository.findByIdAndIsDeletedFalse(quotation.getRfqId())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElse(null);
        if (rfq == null) {
            return SelectionReadiness.notReady("RFQ not found");
        }
        List<SupplierQuotation> quotations = currentSubmitted(rfq.getId()).stream()
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .toList();
        try {
            quotationSelectionValidationService.validateSelection(quotation, rfq, quotations, null);
            if (quotationItemRepository.findByTenantIdAndQuotation_IdAndIsDeletedFalse(TenantContext.currentTenant(), quotation.getId()).stream()
                .noneMatch(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))) {
                return SelectionReadiness.notReady("Selected quotation has no items");
            }
            if (rfq.isAggregatedTender()) {
                return SelectionReadiness.awardAvailable("Aggregated RFQ is awarded to contract, not PO");
            }
            PurchaseOrderDemandCyclePolicy.Readiness cycleReadiness =
                purchaseOrderDemandCyclePolicy.readinessForPurchaseRequest(rfq.getPurchaseRequestId());
            if (!cycleReadiness.ready()) {
                return SelectionReadiness.awardAvailable(cycleReadiness.reason());
            }
            return SelectionReadiness.poAndAwardAvailable();
        } catch (BusinessException ex) {
            return rfq.isAggregatedTender()
                ? SelectionReadiness.awardNotReady("Aggregated RFQ is awarded to contract, not PO", ex.getMessage())
                : SelectionReadiness.notReady(ex.getMessage());
        }
    }

    private List<SupplierQuotation> currentSubmitted(UUID rfqId) {
        List<String> statuses = List.of("ACTIVE", "SUBMITTED", "SELECTED", "REJECTED");
        List<SupplierQuotation> current = repository.findCurrentSubmittedByRfq(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfqId,
            statuses
        );
        if (!current.isEmpty()) {
            return current;
        }
        return repository.findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc(
            TenantContext.currentTenant(), rfqId
        ).stream()
            .filter(value -> value.getThreadId() == null)
            .filter(value -> value.getStatus() == null || statuses.contains(value.getStatus()))
            .toList();
    }

    private void requireCurrentSubmittedRevision(SupplierQuotation quotation) {
        if (quotation.getThreadId() != null && !quotation.isCurrentRevision()) {
            throw new BusinessException("Superseded quotation revision cannot be selected");
        }
        if (quotation.getThreadId() != null
            && !List.of("ACTIVE", "SUBMITTED", "SELECTED", "REJECTED").contains(quotation.getStatus())) {
            throw new BusinessException("Quotation must be submitted before selection");
        }
    }

    private void requireLegacyMutable(SupplierQuotation quotation) {
        if (quotation.getThreadId() != null && !"DRAFT".equals(quotation.getStatus())) {
            throw new BusinessException("Submitted supplier proposal revisions are immutable");
        }
    }

    private record SelectionReadiness(
        boolean poReady,
        String poBlockedReason,
        boolean awardReady,
        String awardBlockedReason
    ) {
        static SelectionReadiness poAndAwardAvailable() {
            return new SelectionReadiness(true, null, true, null);
        }

        static SelectionReadiness notReady(String reason) {
            return new SelectionReadiness(false, reason, false, reason);
        }

        static SelectionReadiness awardAvailable(String poReason) {
            return new SelectionReadiness(false, poReason, true, null);
        }

        static SelectionReadiness awardNotReady(String poReason, String awardReason) {
            return new SelectionReadiness(false, poReason, false, awardReason);
        }
    }

    private void saveSubmittedItems(UUID quotationId, List<SupplierQuotationItemCreateRequest> items, boolean replaceExisting) {
        if (items == null) {
            return;
        }
        if (replaceExisting) {
            quotationItemRepository.findByTenantIdAndQuotation_IdAndIsDeletedFalse(TenantContext.currentTenant(), quotationId)
                .forEach(item -> {
                    item.softDelete();
                    quotationItemRepository.save(item);
                });
        }
        items.forEach(item -> {
            item.setQuotationId(quotationId);
            quotationItemService.create(item);
        });
    }

    private void validateNoDuplicateRfqLines(List<SupplierQuotationItemCreateRequest> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        var seen = new HashSet<UUID>();
        for (SupplierQuotationItemCreateRequest item : items) {
            UUID rfqItemId = item == null ? null : item.getRfqItemId();
            if (rfqItemId != null && !seen.add(rfqItemId)) {
                throw new ConflictException(
                    "Quotation payload contains duplicate RFQ line: " + rfqItemId,
                    "supplier.quotation.line.duplicate",
                    rfqItemId
                );
            }
        }
    }

    private void recalculateTotals(SupplierQuotation entity, SupplierQuotationCreateRequest dto) {
        if (dto.getItems() == null) {
            return;
        }
        BigDecimal total = dto.getItems().stream()
            .map(item -> amount(item.getQuantity()).multiply(amount(item.getUnitPrice())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        entity.setTotalAmount(total);
    }
}

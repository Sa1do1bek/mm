package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.RfqAggregationMode;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.RFQMapper;
import com.enterprise.mm.payload.RfqAggregationDtos.AffectedPurchaseRequestStatus;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateAggregatedRfqRequest;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateAggregatedRfqResponse;
import com.enterprise.mm.payload.RfqAggregationDtos.CreatedRfqLine;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.payload.RfqAggregationDtos.CancelRfqResponse;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RfqAggregationService {
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository rfqItemRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final RFQMapper rfqMapper;
    private final RfqAggregationUnitResolver unitResolver;
    private final PurchaseRequestTenderStatusService tenderStatusService;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;
    private final RoleDataScopeService roleDataScopeService;
    private final SupplierQuotationRepository supplierQuotationRepository;
    private final ContractRepository contractRepository;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;

    @Transactional
    public CreateAggregatedRfqResponse create(CreateAggregatedRfqRequest request) {
        rejectDirectPrSourcing();
        List<UUID> requestedIds = validateAndSortIds(request.purchaseRequestItemIds());
        List<PurchaseRequestItem> items = purchaseRequestItemRepository.findAllByIdInForUpdate(requestedIds);
        if (items.size() != requestedIds.size()) {
            throw new NotFoundException("Purchase request item not found");
        }
        verifyItemBoundary(items);

        List<UUID> parentIds = items.stream()
            .map(PurchaseRequestItem::getPurchaseRequestId)
            .distinct()
            .sorted()
            .toList();
        List<PurchaseRequest> parents = purchaseRequestRepository.findAllByIdInForUpdate(parentIds);
        if (parents.size() != parentIds.size()) {
            throw new NotFoundException("Purchase request not found");
        }
        Map<UUID, PurchaseRequest> parentById = parents.stream()
            .collect(Collectors.toMap(PurchaseRequest::getId, parent -> parent, (left, right) -> left, LinkedHashMap::new));
        Map<UUID, String> previousStatuses = parents.stream()
            .collect(Collectors.toMap(PurchaseRequest::getId, PurchaseRequest::getStatus, (left, right) -> left, LinkedHashMap::new));

        List<ValidatedSelection> selections = new ArrayList<>();
        for (PurchaseRequestItem item : items) {
            PurchaseRequest parent = parentById.get(item.getPurchaseRequestId());
            selections.add(validateSelection(request.groupBy(), item, parent));
        }
        validateSubmittedGroupKey(request, selections);

        RFQ rfq = createRfq(request, selections, parentIds);
        Map<ExactMaterialKey, AggregatedLine> aggregatedLines = aggregateLines(selections);
        List<CreatedRfqLine> responseLines = new ArrayList<>();
        Map<ExactMaterialKey, RFQItem> rfqItemByKey = new LinkedHashMap<>();
        for (Map.Entry<ExactMaterialKey, AggregatedLine> entry : aggregatedLines.entrySet()) {
            RFQItem rfqItem = createRfqItem(rfq, entry.getValue());
            rfqItemByKey.put(entry.getKey(), rfqItem);
            responseLines.add(new CreatedRfqLine(
                rfqItem.getId(),
                rfqItem.getMaterialId(),
                rfqItem.getUnit(),
                entry.getKey().normalizedUnitKey(),
                rfqItem.getQuantity(),
                entry.getValue().sourceCount()));
        }
        for (ValidatedSelection selection : selections) {
            RFQItem rfqItem = rfqItemByKey.get(selection.key());
            createSource(rfq, rfqItem, selection);
        }

        Map<UUID, String> currentStatuses = tenderStatusService.recalculateLocked(parentIds);
        List<AffectedPurchaseRequestStatus> affected = currentStatuses.entrySet().stream()
            .map(entry -> new AffectedPurchaseRequestStatus(
                entry.getKey(),
                previousStatuses.get(entry.getKey()),
                entry.getValue()))
            .toList();

        emitFeatureEvent(rfq, request, selections, responseLines);
        auditService.logInCurrentTransaction("RFQ", rfq.getId(), "CREATE_FROM_PR_ITEMS", rfq.getCode(), null, null);
        for (ValidatedSelection selection : selections) {
            auditService.logInCurrentTransaction(
                "PurchaseRequestItem",
                selection.item().getId(),
                "ALLOCATE_TO_RFQ",
                "rfqId=" + rfq.getId() + ",rfqItemId=" + rfqItemByKey.get(selection.key()).getId()
                    + ",purchaseRequestId=" + selection.parent().getId()
                    + ",quantity=" + selection.item().getQuantity(), null, null);
        }

        return new CreateAggregatedRfqResponse(
            rfq.getId(),
            responseLines.size(),
            parentIds.size(),
            selections.size(),
            responseLines,
            affected);
    }

    @Transactional
    public CreateAggregatedRfqResponse allocateToCampaignRfq(
        RFQ rfq,
        RfqAggregationMode groupBy,
        String groupKey,
        List<UUID> purchaseRequestItemIds
    ) {
        if (rfq == null || rfq.getId() == null) {
            throw new BusinessException("RFQ is required for aggregation allocation");
        }
        if (!TenantContext.currentTenant().equals(rfq.getTenantId())
            || !CompanyContext.currentCompany().equals(rfq.getCompanyId())) {
            throw new NotFoundException("RFQ not found: " + rfq.getId());
        }
        if (sourceRepository.existsByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfq.getId(), RfqSourceAllocationStatus.ACTIVE.name())) {
            throw new BusinessException("Source-backed RFQ structure cannot be changed", "rfq.aggregation.immutable_allocated_source", rfq.getId());
        }
        if (groupBy == null) {
            throw new BusinessException("Aggregation mode is required", "rfq.aggregation.ineligible_line");
        }
        if (groupKey == null || groupKey.isBlank()) {
            throw new BusinessException("Aggregation group key is required", "rfq.aggregation.mixed_boundary");
        }
        List<UUID> requestedIds = validateAndSortIds(purchaseRequestItemIds);
        List<PurchaseRequestItem> items = purchaseRequestItemRepository.findAllByIdInForUpdate(requestedIds);
        if (items.size() != requestedIds.size()) {
            throw new NotFoundException("Purchase request item not found");
        }
        verifyItemBoundary(items);

        List<UUID> parentIds = items.stream()
            .map(PurchaseRequestItem::getPurchaseRequestId)
            .distinct()
            .sorted()
            .toList();
        List<PurchaseRequest> parents = purchaseRequestRepository.findAllByIdInForUpdate(parentIds);
        if (parents.size() != parentIds.size()) {
            throw new NotFoundException("Purchase request not found");
        }
        Map<UUID, PurchaseRequest> parentById = parents.stream()
            .collect(Collectors.toMap(PurchaseRequest::getId, parent -> parent, (left, right) -> left, LinkedHashMap::new));
        Map<UUID, String> previousStatuses = parents.stream()
            .collect(Collectors.toMap(PurchaseRequest::getId, PurchaseRequest::getStatus, (left, right) -> left, LinkedHashMap::new));

        List<ValidatedSelection> selections = new ArrayList<>();
        for (PurchaseRequestItem item : items) {
            PurchaseRequest parent = parentById.get(item.getPurchaseRequestId());
            selections.add(validateSelection(groupBy, item, parent));
        }
        validateSubmittedGroupKey(groupBy, groupKey, selections);

        ValidatedSelection first = selections.get(0);
        rfq.setAggregatedTender(true);
        rfq.setAggregationMode(groupBy);
        rfq.setMaterialGroupId(groupBy == RfqAggregationMode.MATERIAL_GROUP ? first.materialGroupId() : null);
        rfq.setSourcePurchaseRequestCount(parentIds.size());
        rfq.setSourceItemCount(selections.size());
        rfq.setCurrencyCode(first.parent().getCurrencyCode());
        rfq.setPurchaseRequestId(parentIds.size() == 1 ? first.parent().getId() : null);
        rfqRepository.save(rfq);

        Map<ExactMaterialKey, AggregatedLine> aggregatedLines = aggregateLines(selections);
        List<CreatedRfqLine> responseLines = new ArrayList<>();
        Map<ExactMaterialKey, RFQItem> rfqItemByKey = new LinkedHashMap<>();
        for (Map.Entry<ExactMaterialKey, AggregatedLine> entry : aggregatedLines.entrySet()) {
            RFQItem rfqItem = createRfqItem(rfq, entry.getValue());
            rfqItemByKey.put(entry.getKey(), rfqItem);
            responseLines.add(new CreatedRfqLine(
                rfqItem.getId(),
                rfqItem.getMaterialId(),
                rfqItem.getUnit(),
                entry.getKey().normalizedUnitKey(),
                rfqItem.getQuantity(),
                entry.getValue().sourceCount()));
        }
        for (ValidatedSelection selection : selections) {
            RFQItem rfqItem = rfqItemByKey.get(selection.key());
            createSource(rfq, rfqItem, selection);
        }

        Map<UUID, String> currentStatuses = tenderStatusService.recalculateLocked(parentIds);
        List<AffectedPurchaseRequestStatus> affected = currentStatuses.entrySet().stream()
            .map(entry -> new AffectedPurchaseRequestStatus(
                entry.getKey(),
                previousStatuses.get(entry.getKey()),
                entry.getValue()))
            .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rfqId", rfq.getId());
        payload.put("aggregationMode", groupBy);
        payload.put("companyId", rfq.getCompanyId());
        payload.put("currencyCode", rfq.getCurrencyCode());
        payload.put("materialGroupId", rfq.getMaterialGroupId());
        payload.put("sourcePurchaseRequestCount", rfq.getSourcePurchaseRequestCount());
        payload.put("sourceItemCount", rfq.getSourceItemCount());
        payload.put("lines", responseLines);
        payload.put("purchaseRequestIds", selections.stream().map(selection -> selection.parent().getId()).distinct().toList());
        integrationEventService.emitInCurrentTransaction("RFQ_AGGREGATED_ALLOCATED", "RFQ", rfq.getId(), payload);
        auditService.logInCurrentTransaction("RFQ", rfq.getId(), "ALLOCATE_AGGREGATED_PR_ITEMS", groupKey, null, null);
        for (ValidatedSelection selection : selections) {
            auditService.logInCurrentTransaction(
                "PurchaseRequestItem",
                selection.item().getId(),
                "ALLOCATE_TO_CAMPAIGN_RFQ",
                "rfqId=" + rfq.getId() + ",rfqItemId=" + rfqItemByKey.get(selection.key()).getId()
                    + ",purchaseRequestId=" + selection.parent().getId()
                    + ",quantity=" + selection.item().getQuantity(), null, null);
        }

        return new CreateAggregatedRfqResponse(
            rfq.getId(),
            responseLines.size(),
            parentIds.size(),
            selections.size(),
            responseLines,
            affected);
    }

    @Transactional
    public RFQResponse createLegacyFromPurchaseRequest(UUID purchaseRequestId) {
        rejectDirectPrSourcing();
        PurchaseRequest parent = purchaseRequestRepository.findAllByIdInForUpdate(List.of(purchaseRequestId))
            .stream()
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Purchase request not found: " + purchaseRequestId));
        if (!TenantContext.currentTenant().equals(parent.getTenantId())
            || !CompanyContext.currentCompany().equals(parent.getCompanyId())) {
            throw new NotFoundException("Purchase request not found: " + purchaseRequestId);
        }
        if (!roleDataScopeService.canAccessDocument(parent.getCreatedBy(), parent.getRequestedBy(), parent.getDepartmentId())) {
            throw new NotFoundException("Purchase request not found: " + purchaseRequestId);
        }
        if (!PurchaseRequestStatus.APPROVED.name().equals(parent.getStatus())) {
            throw new BusinessException("Purchase request must be APPROVED, current status is " + parent.getStatus());
        }
        List<PurchaseRequestItem> items = purchaseRequestItemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(), purchaseRequestId);
        if (items.isEmpty()) {
            throw new BusinessException("Purchase request has no lines", "rfq.aggregation.empty_selection");
        }
        items.stream()
            .map(PurchaseRequestItem::getId)
            .sorted()
            .forEach(id -> {
                if (sourceRepository.existsByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndStatusAndIsDeletedFalse(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), id, RfqSourceAllocationStatus.ACTIVE.name())) {
                    throw new BusinessException("PR item is already allocated to an RFQ", "rfq.aggregation.already_allocated", id);
                }
            });

        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-" + parent.getCode());
        rfq.setName(BusinessNameGenerator.workflowDocumentName("RFQ", parent.getName()));
        rfq.setStatus("DRAFT");
        rfq.setPackageLanguage("ru");
        rfq.setPurchaseRequest(parent);
        rfq.setDueDate(java.time.LocalDate.now().plusDays(7));
        rfq.setAggregatedTender(false);
        rfq.setSourcePurchaseRequestCount(1);
        rfq.setSourceItemCount(items.size());
        rfq.setCurrencyCode(parent.getCurrencyCode());
        rfq.setCompanyId(CompanyContext.currentCompany());
        RFQ saved = rfqRepository.save(rfq);

        for (PurchaseRequestItem item : items) {
            if (item.getMaterialTypeSnapshot() == null) {
                materialTypeSnapshotService.applySnapshot(item, parent.getRequestType());
            }
            RFQItem rfqItem = copyLegacyRfqItem(saved, item);
            copyLegacySource(saved, rfqItem, parent, item);
        }

        if (parent.getRequestType() == RequestType.GOODS) {
            tenderStatusService.recalculateLocked(List.of(parent.getId()));
        } else {
            parent.setStatus(PurchaseRequestStatus.RFQ_CREATED.name());
            purchaseRequestRepository.save(parent);
        }
        integrationEventService.emitInCurrentTransaction("RFQ_CREATED_FROM_PR", "RFQ", saved.getId(), Map.of(
            "rfqId", saved.getId(),
            "purchaseRequestId", parent.getId(),
            "sourceItemCount", items.size()
        ));
        auditService.logInCurrentTransaction("RFQ", saved.getId(), "CREATE_FROM_PR", parent.getId().toString(), null, null);
        return rfqMapper.toResponse(saved);
    }

    private void rejectDirectPrSourcing() {
        throw new BusinessException(
            "Approved purchase requests must create a procurement method decision before sourcing",
            "procurement.pmd.required"
        );
    }

    @Transactional
    public CancelRfqResponse cancel(UUID rfqId) {
        RFQ rfq = rfqRepository.findByIdForUpdate(rfqId)
            .orElseThrow(() -> new NotFoundException("RFQ not found: " + rfqId));
        if (!TenantContext.currentTenant().equals(rfq.getTenantId())
            || !CompanyContext.currentCompany().equals(rfq.getCompanyId())) {
            throw new NotFoundException("RFQ not found: " + rfqId);
        }
        List<RFQPurchaseRequestSource> allSources = sourceRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId);
        if ("CANCELLED".equals(rfq.getStatus())) {
            return new CancelRfqResponse(
                rfq.getId(),
                (int) allSources.stream().filter(source -> RfqSourceAllocationStatus.RELEASED.name().equals(source.getStatus())).count(),
                currentStatuses(allSources));
        }
        if (supplierQuotationRepository.existsByTenantIdAndCompanyIdAndRfqIdAndSelectedTrueAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId)) {
            throw new BusinessException("RFQ cannot be cancelled after quotation selection", "rfq.aggregation.cancel_after_award", rfqId);
        }
        if (contractRepository.existsByTenantIdAndCompanyIdAndSourceRfqIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId)) {
            throw new BusinessException("RFQ cannot be cancelled after contract creation", "rfq.aggregation.cancel_after_award", rfqId);
        }

        List<RFQPurchaseRequestSource> activeSources = sourceRepository.findActiveByRfqIdForUpdate(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfqId,
            RfqSourceAllocationStatus.ACTIVE.name());
        List<UUID> itemIds = activeSources.stream()
            .map(RFQPurchaseRequestSource::getPurchaseRequestItemId)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        if (!itemIds.isEmpty()) {
            purchaseRequestItemRepository.findAllByIdInForUpdate(itemIds);
        }
        List<UUID> purchaseRequestIds = activeSources.stream()
            .map(RFQPurchaseRequestSource::getPurchaseRequestId)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        if (!purchaseRequestIds.isEmpty()) {
            purchaseRequestRepository.findAllByIdInForUpdate(purchaseRequestIds);
        }

        LocalDateTime releasedAt = LocalDateTime.now();
        for (RFQPurchaseRequestSource source : activeSources) {
            source.setStatus(RfqSourceAllocationStatus.RELEASED.name());
            source.setReleasedAt(releasedAt);
            sourceRepository.save(source);
        }
        rfq.setStatus("CANCELLED");
        rfqRepository.save(rfq);
        Map<UUID, String> recalculated = tenderStatusService.recalculateLocked(purchaseRequestIds);
        List<AffectedPurchaseRequestStatus> affected = recalculated.entrySet().stream()
            .map(entry -> new AffectedPurchaseRequestStatus(entry.getKey(), null, entry.getValue()))
            .toList();
        integrationEventService.emitInCurrentTransaction("RFQ_CANCELLED", "RFQ", rfq.getId(), Map.of(
            "rfqId", rfq.getId(),
            "releasedSourceCount", activeSources.size(),
            "purchaseRequestIds", purchaseRequestIds
        ));
        auditService.logInCurrentTransaction("RFQ", rfq.getId(), "CANCEL", rfq.getCode(), null, null);
        for (RFQPurchaseRequestSource source : activeSources) {
            auditService.logInCurrentTransaction("RFQPurchaseRequestSource", source.getId(), "RELEASE", rfq.getId().toString(), null, null);
        }
        return new CancelRfqResponse(rfq.getId(), activeSources.size(), affected);
    }

    public void assertPurchaseRequestItemMutable(UUID purchaseRequestItemId) {
        if (sourceRepository.existsByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestItemId, RfqSourceAllocationStatus.ACTIVE.name())) {
            throw new BusinessException("Allocated purchase request item cannot be changed", "rfq.aggregation.immutable_allocated_source", purchaseRequestItemId);
        }
    }

    public void assertPurchaseRequestMutable(UUID purchaseRequestId) {
        if (sourceRepository.existsByTenantIdAndCompanyIdAndPurchaseRequestIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseRequestId, RfqSourceAllocationStatus.ACTIVE.name())) {
            throw new BusinessException("Allocated purchase request cannot be changed", "rfq.aggregation.immutable_allocated_source", purchaseRequestId);
        }
    }

    public void assertRfqStructureMutable(UUID rfqId) {
        if (sourceRepository.existsByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId, RfqSourceAllocationStatus.ACTIVE.name())) {
            throw new BusinessException("Source-backed RFQ structure cannot be changed", "rfq.aggregation.immutable_allocated_source", rfqId);
        }
    }

    public void assertRfqItemMutable(UUID rfqItemId) {
        if (sourceRepository.existsByTenantIdAndCompanyIdAndRfqItemIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqItemId)) {
            throw new BusinessException("Source-backed RFQ item cannot be changed", "rfq.aggregation.immutable_allocated_source", rfqItemId);
        }
    }

    private List<AffectedPurchaseRequestStatus> currentStatuses(List<RFQPurchaseRequestSource> sources) {
        List<UUID> purchaseRequestIds = sources.stream()
            .map(RFQPurchaseRequestSource::getPurchaseRequestId)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        if (purchaseRequestIds.isEmpty()) {
            return List.of();
        }
        return purchaseRequestRepository.findAllById(purchaseRequestIds).stream()
            .map(request -> new AffectedPurchaseRequestStatus(request.getId(), request.getStatus(), request.getStatus()))
            .toList();
    }

    private RFQItem copyLegacyRfqItem(RFQ rfq, PurchaseRequestItem item) {
        RFQItem rfqItem = new RFQItem();
        rfqItem.setCode("RFQI-" + UUID.randomUUID().toString().substring(0, 8));
        rfqItem.setName(item.getName() == null ? "RFQ item" : item.getName());
        rfqItem.setStatus("OPEN");
        rfqItem.setCompanyId(CompanyContext.currentCompany());
        rfqItem.setRfqId(rfq.getId());
        rfqItem.setLineType(item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType());
        rfqItem.setMaterialId(item.getMaterialId());
        rfqItem.setMaterialTypeSnapshot(item.getMaterialTypeSnapshot());
        rfqItem.setServiceRequestId(item.getServiceRequestId());
        rfqItem.setServiceDescription(item.getServiceDescription());
        rfqItem.setServiceCategory(item.getServiceCategory());
        rfqItem.setUnit(item.getUnit());
        rfqItem.setPeriod(item.getPeriod());
        rfqItem.setStage(item.getStage());
        rfqItem.setBudgetItemId(item.getBudgetItemId());
        rfqItem.setQuantity(item.getQuantity());
        return rfqItemRepository.save(rfqItem);
    }

    private void copyLegacySource(RFQ rfq, RFQItem rfqItem, PurchaseRequest parent, PurchaseRequestItem item) {
        if (item.getQuantity() == null || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Purchase request line quantity must be positive", "rfq.aggregation.ineligible_line", item.getId());
        }
        RFQPurchaseRequestSource source = new RFQPurchaseRequestSource();
        source.setCode("RFQSRC-" + UUID.randomUUID().toString().substring(0, 8));
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
        source.setSourceUnit(item.getUnit());
        source.setAllocatedAt(LocalDateTime.now());
        sourceRepository.save(source);
    }

    private List<UUID> validateAndSortIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("Select at least one PR item", "rfq.aggregation.empty_selection");
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException("PR item selection contains null", "rfq.aggregation.ineligible_line");
        }
        Set<UUID> unique = ids.stream().collect(Collectors.toSet());
        if (unique.size() != ids.size()) {
            throw new BusinessException("Duplicate PR item selection", "rfq.aggregation.duplicate_ids");
        }
        return ids.stream().sorted().toList();
    }

    private void verifyItemBoundary(List<PurchaseRequestItem> items) {
        for (PurchaseRequestItem item : items) {
            if (!TenantContext.currentTenant().equals(item.getTenantId())
                || !CompanyContext.currentCompany().equals(item.getCompanyId())) {
                throw new NotFoundException("Purchase request item not found: " + item.getId());
            }
        }
    }

    private ValidatedSelection validateSelection(RfqAggregationMode mode, PurchaseRequestItem item, PurchaseRequest parent) {
        if (parent == null
            || !TenantContext.currentTenant().equals(parent.getTenantId())
            || !CompanyContext.currentCompany().equals(parent.getCompanyId())) {
            throw new NotFoundException("Purchase request not found: " + item.getPurchaseRequestId());
        }
        if (!roleDataScopeService.canAccessDocument(parent.getCreatedBy(), parent.getRequestedBy(), parent.getDepartmentId())) {
            throw new NotFoundException("Purchase request not found: " + parent.getId());
        }
        if (!List.of(PurchaseRequestStatus.APPROVED.name(), PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name()).contains(parent.getStatus())
            || parent.getRequestType() != RequestType.GOODS
            || item.isDeleted()
            || parent.isDeleted()
            || item.getLineType() != ProcurementLineType.MATERIAL
            || item.getQuantity() == null
            || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("PR item is not eligible for RFQ aggregation", "rfq.aggregation.ineligible_line", item.getId());
        }
        Material material = item.getMaterial();
        if (material == null
            || material.isDeleted()
            || !TenantContext.currentTenant().equals(material.getTenantId())
            || !CompanyContext.currentCompany().equals(material.getCompanyId())
            || !"ACTIVE".equals(material.getStatus())) {
            throw new BusinessException("PR item material is not eligible for RFQ aggregation", "rfq.aggregation.ineligible_line", item.getId());
        }
        if (sourceRepository.existsByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndStatusAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), item.getId(), RfqSourceAllocationStatus.ACTIVE.name())) {
            throw new BusinessException("PR item is already allocated to an RFQ", "rfq.aggregation.already_allocated", item.getId());
        }
        if (item.getMaterialTypeSnapshot() == null) {
            materialTypeSnapshotService.applySnapshot(item, parent.getRequestType());
        }
        String displayUnit = unitResolver.resolve(item);
        String unitKey = unitResolver.requiredKey(displayUnit, item.getId());
        UUID materialGroupId = material.getMaterialGroupId();
        if (mode == RfqAggregationMode.MATERIAL_GROUP && materialGroupId == null) {
            throw new BusinessException("Material group is required for RFQ aggregation", "rfq.aggregation.material_group_required", item.getId());
        }
        return new ValidatedSelection(
            item,
            parent,
            material,
            new ExactMaterialKey(item.getMaterialId(), item.getMaterialTypeSnapshot(), unitKey, item.fulfillmentDate(parent)),
            displayUnit,
            materialGroupId);
    }

    private void validateSubmittedGroupKey(CreateAggregatedRfqRequest request, List<ValidatedSelection> selections) {
        validateSubmittedGroupKey(request.groupBy(), request.groupKey(), selections);
    }

    private void validateSubmittedGroupKey(RfqAggregationMode groupBy, String groupKey, List<ValidatedSelection> selections) {
        String expected = computeGroupKey(groupBy, selections.get(0));
        boolean sameBoundary = selections.stream().allMatch(selection -> expected.equals(computeGroupKey(groupBy, selection)));
        if (!sameBoundary || !expected.equals(groupKey)) {
            throw new BusinessException("Selected PR items do not match RFQ aggregation group", "rfq.aggregation.mixed_boundary");
        }
    }

    private String computeGroupKey(RfqAggregationMode mode, ValidatedSelection selection) {
        String currency = selection.parent().getCurrencyCode() == null ? "" : selection.parent().getCurrencyCode().toUpperCase(Locale.ROOT);
        if (mode == RfqAggregationMode.MATERIAL_GROUP) {
            return "MATERIAL_GROUP|" + selection.parent().getCompanyId() + "|" + currency + "|" + selection.materialGroupId();
        }
            return "MATERIAL|" + selection.parent().getCompanyId() + "|" + currency + "|" + selection.item().getMaterialId()
                + "|" + selection.key().materialTypeSnapshot() + "|" + selection.key().normalizedUnitKey();
    }

    private RFQ createRfq(CreateAggregatedRfqRequest request, List<ValidatedSelection> selections, Collection<UUID> parentIds) {
        ValidatedSelection first = selections.get(0);
        RFQ rfq = new RFQ();
        rfq.setCode("RFQ-AGG-" + UUID.randomUUID().toString().substring(0, 8));
        rfq.setName(BusinessNameGenerator.workflowDocumentName("RFQ", aggregationParentName(selections)));
        rfq.setStatus("DRAFT");
        rfq.setDueDate(request.dueDate());
        rfq.setTenderProcedureType(request.tenderProcedureType());
        rfq.setSelectionRule(request.selectionRule());
        rfq.setMinQuotationsRequired(request.minQuotationsRequired());
        rfq.setResponsibleBuyer(request.responsibleBuyer());
        rfq.setResponseInstructions(request.responseInstructions());
        rfq.setPackageLanguage(normalizePackageLanguage(request.packageLanguage()));
        rfq.setAggregatedTender(true);
        rfq.setAggregationMode(request.groupBy());
        rfq.setMaterialGroupId(request.groupBy() == RfqAggregationMode.MATERIAL_GROUP ? first.materialGroupId() : null);
        rfq.setSourcePurchaseRequestCount(parentIds.size());
        rfq.setSourceItemCount(selections.size());
        rfq.setCurrencyCode(first.parent().getCurrencyCode());
        rfq.setPurchaseRequestId(parentIds.size() == 1 ? first.parent().getId() : null);
        rfq.setCompanyId(CompanyContext.currentCompany());
        return rfqRepository.save(rfq);
    }

    private String aggregationParentName(List<ValidatedSelection> selections) {
        Map<String, String> parentNamesByBase = selections.stream()
            .map(ValidatedSelection::parent)
            .filter(Objects::nonNull)
            .map(PurchaseRequest::getName)
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(
                name -> {
                    String base = BusinessNameGenerator.workflowBaseName(name, "PurchaseRequest");
                    return base == null ? "" : base;
                },
                name -> name,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        if (parentNamesByBase.size() == 1) {
            return parentNamesByBase.values().iterator().next();
        }
        return "Aggregated procurement";
    }

    private String normalizePackageLanguage(String value) {
        String normalized = value == null || value.isBlank()
            ? "ru"
            : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ru", "uz", "en" -> normalized;
            default -> throw new BusinessException("Unsupported RFQ package language: " + value);
        };
    }

    private Map<ExactMaterialKey, AggregatedLine> aggregateLines(List<ValidatedSelection> selections) {
        Map<ExactMaterialKey, AggregatedLine> lines = new LinkedHashMap<>();
        selections.stream()
            .sorted(Comparator.comparing(selection -> selection.material().getCode(), Comparator.nullsLast(String::compareTo)))
            .forEach(selection -> lines.compute(selection.key(), (key, existing) -> {
                if (existing == null) {
                    return new AggregatedLine(
                        selection.material(),
                        selection.item().getMaterialTypeSnapshot(),
                        selection.displayUnit(),
                        selection.item().getQuantity(),
                        1
                    );
                }
                return existing.add(selection.item().getQuantity());
            }));
        return lines;
    }

    private RFQItem createRfqItem(RFQ rfq, AggregatedLine line) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-" + UUID.randomUUID().toString().substring(0, 8));
        item.setName(line.material().getName() == null ? "RFQ item" : line.material().getName());
        item.setStatus("OPEN");
        item.setCompanyId(CompanyContext.currentCompany());
        item.setRfqId(rfq.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(line.material().getId());
        item.setMaterialTypeSnapshot(line.materialTypeSnapshot());
        item.setUnit(line.unit());
        item.setQuantity(line.quantity());
        return rfqItemRepository.save(item);
    }

    private void createSource(RFQ rfq, RFQItem rfqItem, ValidatedSelection selection) {
        RFQPurchaseRequestSource source = new RFQPurchaseRequestSource();
        source.setCode("RFQSRC-" + UUID.randomUUID().toString().substring(0, 8));
        source.setName(selection.parent().getName() + " allocation");
        source.setStatus(RfqSourceAllocationStatus.ACTIVE.name());
        source.setCompanyId(CompanyContext.currentCompany());
        source.setRfqId(rfq.getId());
        source.setRfqItemId(rfqItem.getId());
        source.setPurchaseRequestId(selection.parent().getId());
        source.setPurchaseRequestItemId(selection.item().getId());
        source.setAllocatedQuantity(selection.item().getQuantity());
        source.setSourceRequiredDate(selection.item().fulfillmentDate(selection.parent()));
        source.setSourceDepartmentId(selection.parent().getDepartmentId());
        source.setSourceBudgetItemId(selection.item().getBudgetItemId());
        source.setSourceCurrencyCode(selection.parent().getCurrencyCode());
        source.setSourceUnit(selection.displayUnit());
        source.setAllocatedAt(LocalDateTime.now());
        sourceRepository.save(source);
    }

    private void emitFeatureEvent(
        RFQ rfq,
        CreateAggregatedRfqRequest request,
        List<ValidatedSelection> selections,
        List<CreatedRfqLine> lines
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rfqId", rfq.getId());
        payload.put("aggregationMode", request.groupBy());
        payload.put("companyId", rfq.getCompanyId());
        payload.put("currencyCode", rfq.getCurrencyCode());
        payload.put("materialGroupId", rfq.getMaterialGroupId());
        payload.put("sourcePurchaseRequestCount", rfq.getSourcePurchaseRequestCount());
        payload.put("sourceItemCount", rfq.getSourceItemCount());
        payload.put("lines", lines);
        payload.put("purchaseRequestIds", selections.stream().map(selection -> selection.parent().getId()).distinct().toList());
        integrationEventService.emitInCurrentTransaction("RFQ_AGGREGATED_CREATED", "RFQ", rfq.getId(), payload);
    }

    private record ExactMaterialKey(UUID materialId, MaterialType materialTypeSnapshot, String normalizedUnitKey, LocalDate requiredDate) {
    }

    private record ValidatedSelection(
        PurchaseRequestItem item,
        PurchaseRequest parent,
        Material material,
        ExactMaterialKey key,
        String displayUnit,
        UUID materialGroupId) {
    }

    private record AggregatedLine(Material material, MaterialType materialTypeSnapshot, String unit, BigDecimal quantity, int sourceCount) {
        AggregatedLine add(BigDecimal additionalQuantity) {
            return new AggregatedLine(material, materialTypeSnapshot, unit, quantity.add(additionalQuantity), sourceCount + 1);
        }
    }
}

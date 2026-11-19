package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.RfqAggregationDtos.AggregationPreviewFilter;
import com.enterprise.mm.payload.RfqAggregationDtos.Reference;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationCandidateRow;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationDocumentScope;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationPreviewGroup;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationPreviewLine;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationSourceRow;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqSourceAllocation;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqSourceLine;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqSourcesResponse;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RfqAggregationQueryRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RfqAggregationQueryService {
    private final RfqAggregationQueryRepository queryRepository;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository rfqItemRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final RoleDataScopeService roleDataScopeService;

    @Transactional(readOnly = true)
    public Page<RfqAggregationPreviewGroup> preview(AggregationPreviewFilter filter, Pageable pageable) {
        RfqAggregationDocumentScope scope = currentScope();
        var groupPage = queryRepository.findGroupPage(filter, scope, pageable);
        var candidates = queryRepository.findCandidates(filter, scope, groupPage.getContent());
        Map<String, List<RfqAggregationCandidateRow>> candidatesByGroup = candidates.stream()
            .collect(Collectors.groupingBy(
                RfqAggregationCandidateRow::groupKey,
                LinkedHashMap::new,
                Collectors.toList()));
        List<RfqAggregationPreviewGroup> groups = groupPage.getContent().stream()
            .map(group -> toPreviewGroup(group, candidatesByGroup.getOrDefault(group.groupKey(), List.of())))
            .toList();
        return new PageImpl<>(groups, pageable, groupPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public RfqSourcesResponse sources(UUID rfqId) {
        RFQ rfq = rfqRepository.findByIdAndIsDeletedFalse(rfqId)
            .orElseThrow(() -> new NotFoundException("RFQ not found: " + rfqId));
        if (!TenantContext.currentTenant().equals(rfq.getTenantId())
            || !CompanyContext.currentCompany().equals(rfq.getCompanyId())) {
            throw new NotFoundException("RFQ not found: " + rfqId);
        }
        if (rfq.getPurchaseRequest() != null
            && !roleDataScopeService.canAccessDocument(
                rfq.getPurchaseRequest().getCreatedBy(),
                rfq.getPurchaseRequest().getRequestedBy(),
                rfq.getPurchaseRequest().getDepartmentId())) {
            throw new NotFoundException("RFQ not found: " + rfqId);
        }
        List<RFQPurchaseRequestSource> sources = sourceRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId);
        if (!canAccessSources(rfq, sources)) {
            throw new NotFoundException("RFQ not found: " + rfqId);
        }
        if (sources.isEmpty()) {
            return new RfqSourcesResponse(
                rfq.getId(),
                rfq.isAggregatedTender(),
                rfq.getPurchaseRequestId() != null,
                0,
                0,
                List.of());
        }
        Map<UUID, RFQItem> itemById = rfqItemRepository
            .findByTenantIdAndRfq_IdAndIsDeletedFalse(TenantContext.currentTenant(), rfqId)
            .stream()
            .collect(Collectors.toMap(RFQItem::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Map<UUID, List<RFQPurchaseRequestSource>> byRfqItem = sources.stream()
            .collect(Collectors.groupingBy(
                RFQPurchaseRequestSource::getRfqItemId,
                LinkedHashMap::new,
                Collectors.toList()));
        List<RfqSourceLine> lines = byRfqItem.entrySet().stream()
            .map(entry -> toSourceLine(itemById.get(entry.getKey()), entry.getValue()))
            .toList();
        int prCount = sources.stream()
            .map(RFQPurchaseRequestSource::getPurchaseRequestId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet())
            .size();
        return new RfqSourcesResponse(rfq.getId(), rfq.isAggregatedTender(), false, prCount, sources.size(), lines);
    }

    private RfqAggregationPreviewGroup toPreviewGroup(
        com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationGroupKeyRow group,
        List<RfqAggregationCandidateRow> candidates
    ) {
        List<RfqAggregationSourceRow> sourceRows = candidates.stream()
            .map(this::toSourceRow)
            .toList();
        Map<ExactLineKey, List<RfqAggregationCandidateRow>> byLine = candidates.stream()
            .collect(Collectors.groupingBy(
                row -> new ExactLineKey(row.materialId(), row.normalizedUnitKey(), row.requiredDate()),
                LinkedHashMap::new,
                Collectors.toList()));
        List<RfqAggregationPreviewLine> lines = byLine.values().stream()
            .map(rows -> toPreviewLine(rows, sourceRows))
            .toList();
        return new RfqAggregationPreviewGroup(
            group.groupKey(),
            group.groupBy(),
            group.companyId(),
            group.currencyCode(),
            group.materialGroupId(),
            group.materialGroupCode(),
            group.materialGroupName(),
            zero(group.totalQuantity()),
            zero(group.estimatedAmount()),
            Math.toIntExact(group.purchaseRequestCount()),
            Math.toIntExact(group.itemCount()),
            group.earliestRequiredDate(),
            group.latestRequiredDate(),
            references(candidates, RfqAggregationCandidateRow::departmentId, RfqAggregationCandidateRow::departmentCode, RfqAggregationCandidateRow::departmentName),
            references(candidates, RfqAggregationCandidateRow::budgetItemId, RfqAggregationCandidateRow::budgetItemCode, RfqAggregationCandidateRow::budgetItemName),
            lines,
            sourceRows);
    }

    private RfqAggregationPreviewLine toPreviewLine(
        List<RfqAggregationCandidateRow> rows,
        List<RfqAggregationSourceRow> allSources
    ) {
        RfqAggregationCandidateRow first = rows.get(0);
        Set<UUID> ids = rows.stream().map(RfqAggregationCandidateRow::purchaseRequestItemId).collect(Collectors.toSet());
        List<RfqAggregationSourceRow> sources = allSources.stream()
            .filter(source -> ids.contains(source.purchaseRequestItemId()))
            .toList();
        BigDecimal quantity = rows.stream().map(RfqAggregationCandidateRow::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = rows.stream()
            .map(row -> row.quantity().multiply(row.estimatedPrice()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RfqAggregationPreviewLine(
            first.materialId(),
            first.materialCode(),
            first.materialName(),
            first.unit(),
            first.normalizedUnitKey(),
            quantity,
            amount,
            rows.size(),
            sources);
    }

    private RfqAggregationSourceRow toSourceRow(RfqAggregationCandidateRow row) {
        BigDecimal estimatedAmount = row.quantity().multiply(row.estimatedPrice());
        return new RfqAggregationSourceRow(
            row.purchaseRequestId(),
            row.purchaseRequestItemId(),
            row.purchaseRequestCode(),
            row.purchaseRequestName(),
            row.purchaseRequestItemCode(),
            row.departmentId(),
            row.departmentCode(),
            row.departmentName(),
            row.budgetItemId(),
            row.budgetItemCode(),
            row.budgetItemName(),
            row.materialId(),
            row.materialCode(),
            row.materialName(),
            row.quantity(),
            row.estimatedPrice(),
            estimatedAmount,
            row.unit(),
            row.normalizedUnitKey(),
            row.requiredDate(),
            row.currencyCode());
    }

    private RfqSourceLine toSourceLine(RFQItem rfqItem, List<RFQPurchaseRequestSource> rows) {
        RFQPurchaseRequestSource first = rows.get(0);
        BigDecimal quantity = rfqItem == null ? first.getAllocatedQuantity() : rfqItem.getQuantity();
        UUID materialId = rfqItem == null ? null : rfqItem.getMaterialId();
        String unit = rfqItem == null ? first.getSourceUnit() : rfqItem.getUnit();
        return new RfqSourceLine(
            first.getRfqItemId(),
            materialId,
            rfqItem == null || rfqItem.getMaterial() == null ? null : rfqItem.getMaterial().getCode(),
            rfqItem == null || rfqItem.getMaterial() == null ? null : rfqItem.getMaterial().getName(),
            unit,
            zero(quantity),
            rows.stream().map(this::toSourceAllocation).toList());
    }

    private RfqSourceAllocation toSourceAllocation(RFQPurchaseRequestSource source) {
        return new RfqSourceAllocation(
            source.getId(),
            source.getPurchaseRequestId(),
            source.getPurchaseRequestItemId(),
            source.getAllocatedQuantity(),
            source.getSourceRequiredDate(),
            source.getSourceDepartmentId(),
            source.getSourceBudgetItemId(),
            source.getSourceCurrencyCode(),
            source.getSourceUnit(),
            source.getStatus(),
            source.getAllocatedAt(),
            source.getReleasedAt());
    }

    private RfqAggregationDocumentScope currentScope() {
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        return new RfqAggregationDocumentScope(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            scope.hasDocumentScope(),
            scope.ownPrincipals(),
            scope.teamPrincipals(),
            scope.managedDepartmentIds());
    }

    private boolean canAccessSources(RFQ rfq, List<RFQPurchaseRequestSource> sources) {
        RoleDataScopeService.DataScope scope = roleDataScopeService.currentScope();
        if (!scope.hasDocumentScope() || sources.isEmpty()) {
            return true;
        }
        Set<UUID> purchaseRequestIds = sources.stream()
            .map(RFQPurchaseRequestSource::getPurchaseRequestId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        List<PurchaseRequest> purchaseRequests = purchaseRequestRepository.findAllById(purchaseRequestIds);
        if (purchaseRequests.size() != purchaseRequestIds.size()) {
            return false;
        }
        return purchaseRequests.stream().allMatch(pr -> roleDataScopeService.canAccessDocument(
            pr.getCreatedBy(), pr.getRequestedBy(), pr.getDepartmentId()));
    }

    private List<Reference> references(
        List<RfqAggregationCandidateRow> rows,
        java.util.function.Function<RfqAggregationCandidateRow, UUID> id,
        java.util.function.Function<RfqAggregationCandidateRow, String> code,
        java.util.function.Function<RfqAggregationCandidateRow, String> name
    ) {
        Map<UUID, Reference> references = new LinkedHashMap<>();
        rows.forEach(row -> {
            UUID value = id.apply(row);
            if (value != null) {
                references.putIfAbsent(value, new Reference(value, code.apply(row), name.apply(row)));
            }
        });
        return references.values().stream()
            .sorted(Comparator.comparing(ref -> Objects.toString(ref.code(), "")))
            .toList();
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ExactLineKey(UUID materialId, String normalizedUnitKey, java.time.LocalDate requiredDate) {
    }
}

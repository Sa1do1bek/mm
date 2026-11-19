package com.enterprise.mm.service;

import com.enterprise.mm.payload.MaterialCommandViewDtos.DemandSupplyFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.DemandSupplyRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.LinkTarget;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey;
import com.enterprise.mm.repository.MaterialDemandSupplyQueryRepository;
import com.enterprise.mm.repository.MaterialDemandSupplyQueryRepository.DemandSupplyRecord;
import com.enterprise.mm.repository.MaterialDemandSupplyQueryRepository.DemandSupplyTotals;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialDemandSupplyQueryService {
    private final MaterialDemandSupplyQueryRepository repository;

    public Page<DemandSupplyRow> page(
        MaterialScope scope,
        DemandSupplyFilter filter,
        Set<SectionKey> readableSections,
        Pageable pageable
    ) {
        Pageable boundedPageable = bounded(pageable);
        Set<SectionKey> sections = readableSections == null ? Set.of() : Set.copyOf(readableSections);
        boolean includePurchaseRequests = sections.contains(SectionKey.PURCHASE_REQUESTS);
        boolean includeReservations = sections.contains(SectionKey.RESERVATIONS);
        boolean includePurchaseOrders = sections.contains(SectionKey.PURCHASE_ORDERS);
        if (!includePurchaseRequests && !includeReservations && !includePurchaseOrders) {
            return new PageImpl<>(java.util.List.of(), boundedPageable, 0);
        }

        DemandSupplyFilter requestedFilter = filter == null ? DemandSupplyFilter.empty() : filter;
        DemandSupplyFilter effectiveFilter = new DemandSupplyFilter(
            requestedFilter.dateFrom(),
            requestedFilter.dateTo(),
            scope.warehouseId() == null ? requestedFilter.warehouseId() : scope.warehouseId(),
            requestedFilter.status());
        return repository.page(
                scope.tenantId(),
                scope.companyId(),
                scope.materialId(),
                includePurchaseRequests,
                includeReservations,
                includePurchaseOrders,
                effectiveFilter,
                boundedPageable)
            .map(this::map);
    }

    public boolean overdueSupplyExists(
        MaterialScope scope,
        Set<SectionKey> readableSections,
        LocalDate asOfDate
    ) {
        if (readableSections == null || !readableSections.contains(SectionKey.PURCHASE_ORDERS)) {
            return false;
        }
        return repository.overdueSupplyExists(
            scope.tenantId(),
            scope.companyId(),
            scope.materialId(),
            scope.warehouseId(),
            asOfDate);
    }

    public DemandSupplyTotals totals(MaterialScope scope, Set<SectionKey> readableSections) {
        Set<SectionKey> sections = readableSections == null ? Set.of() : readableSections;
        boolean requests = sections.contains(SectionKey.PURCHASE_REQUESTS);
        boolean reservations = sections.contains(SectionKey.RESERVATIONS);
        boolean orders = sections.contains(SectionKey.PURCHASE_ORDERS);
        if (!requests && !reservations && !orders) {
            return new DemandSupplyTotals(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        }
        return repository.totals(scope.tenantId(), scope.companyId(), scope.materialId(), requests, reservations,
            orders, scope.warehouseId());
    }

    private DemandSupplyRow map(DemandSupplyRecord record) {
        return new DemandSupplyRow(
            record.id(),
            record.kind(),
            record.name(),
            record.documentCode(),
            record.documentStatus(),
            record.openQuantity() == null ? null : record.openQuantity().toPlainString(),
            record.unitOfMeasureCode(),
            record.unitOfMeasureShortName(),
            record.relevantDate(),
            record.warehouseId(),
            record.warehouseName(),
            LinkTarget.of(record.moduleKey(), record.recordId(), Map.of()));
    }

    private Pageable bounded(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, MaterialInventoryQueryService.DEFAULT_PAGE_SIZE);
        }
        return PageRequest.of(
            Math.max(pageable.getPageNumber(), 0),
            Math.min(pageable.getPageSize(), MaterialInventoryQueryService.MAX_PAGE_SIZE));
    }
}

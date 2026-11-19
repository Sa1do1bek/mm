package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.procurement.command.enums.ProcurementAllocationMode;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AllocationBranchRef;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.LineCoverage;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.SourcingCoverageSnapshot;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class CompatibilitySourcingCoverageReader implements SourcingCoverageReader {

    private final PurchaseRequestItemRepository itemRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final RFQRepository rfqRepository;

    public CompatibilitySourcingCoverageReader(
        PurchaseRequestItemRepository itemRepository,
        RFQPurchaseRequestSourceRepository sourceRepository,
        RFQRepository rfqRepository
    ) {
        this.itemRepository = itemRepository;
        this.sourceRepository = sourceRepository;
        this.rfqRepository = rfqRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public SourcingCoverageSnapshot read(UUID purchaseRequestId) {
        List<PurchaseRequestItem> items = itemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseRequestId)
            .stream()
            .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
            .toList();
        List<RFQPurchaseRequestSource> sources = sourceRepository
            .findByTenantIdAndPurchaseRequestIdAndStatusAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseRequestId, RfqSourceAllocationStatus.ACTIVE.name())
            .stream()
            .filter(source -> AccessScope.canAccessCompany(source.getCompanyId()))
            .toList();
        Map<UUID, List<RFQPurchaseRequestSource>> sourcesByItem = sources.stream()
            .collect(Collectors.groupingBy(RFQPurchaseRequestSource::getPurchaseRequestItemId));
        List<UUID> rfqIds = sources.stream().map(RFQPurchaseRequestSource::getRfqId).distinct().toList();
        Map<UUID, RFQ> rfqsById = rfqIds.isEmpty()
            ? Map.of()
            : rfqRepository
                .findByTenantIdAndIdInAndIsDeletedFalse(TenantContext.currentTenant(), rfqIds)
                .stream()
                .filter(rfq -> AccessScope.canAccessCompany(rfq.getCompanyId()))
                .collect(Collectors.toMap(RFQ::getId, Function.identity()));
        List<LineCoverage> lines = items.stream()
            .map(item -> authoritativeCoverage(item, sourcesByItem.getOrDefault(item.getId(), List.of()), rfqsById))
            .toList();
        return new SourcingCoverageSnapshot(
            ProcurementAllocationMode.AUTHORITATIVE,
            lines,
            LocalDateTime.now());
    }

    private LineCoverage authoritativeCoverage(
        PurchaseRequestItem item,
        List<RFQPurchaseRequestSource> sources,
        Map<UUID, RFQ> rfqsById
    ) {
        String displayName = item.getName();
        if ((displayName == null || displayName.isBlank())
            && item.getServiceDescription() != null) {
            displayName = item.getServiceDescription();
        }
        BigDecimal requested = zeroIfNull(item.getQuantity());
        BigDecimal allocated = sources.stream()
            .map(RFQPurchaseRequestSource::getAllocatedQuantity)
            .map(this::zeroIfNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = requested.subtract(allocated).max(BigDecimal.ZERO);
        BigDecimal coveragePercent = allocated.signum() > 0
            ? new BigDecimal("100.00")
            : BigDecimal.ZERO;
        List<AllocationBranchRef> branches = sources.stream()
            .map(source -> {
                RFQ rfq = rfqsById.get(source.getRfqId());
                return new AllocationBranchRef(
                    "RFQ",
                    source.getRfqId(),
                    source.getRfqItemId(),
                    zeroIfNull(source.getAllocatedQuantity()),
                    rfq == null ? null : rfq.getStatus());
            })
            .toList();
        return new LineCoverage(
            item.getId(),
            item.getMaterialId(),
            displayName,
            item.getUnit(),
            requested,
            allocated,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            remaining,
            coveragePercent,
            branches);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

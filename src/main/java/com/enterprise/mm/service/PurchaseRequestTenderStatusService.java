package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseRequestTenderStatusService {
    private static final Set<String> RECALCULATED_STATUSES = Set.of(
        PurchaseRequestStatus.APPROVED.name(),
        PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
        PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name(),
        PurchaseRequestStatus.RFQ_CREATED.name());

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, String> recalculateLocked(Collection<UUID> purchaseRequestIds) {
        if (purchaseRequestIds == null || purchaseRequestIds.isEmpty()) {
            return Map.of();
        }
        List<PurchaseRequest> requests = purchaseRequestRepository.findAllByIdInForUpdate(purchaseRequestIds);
        Map<UUID, String> statuses = new LinkedHashMap<>();
        for (PurchaseRequest request : requests) {
            if (!TenantContext.currentTenant().equals(request.getTenantId())
                || !CompanyContext.currentCompany().equals(request.getCompanyId())
                || !RECALCULATED_STATUSES.contains(request.getStatus())) {
                continue;
            }
            long activeAllocations = sourceRepository
                .findByTenantIdAndCompanyIdAndPurchaseRequestIdAndStatusAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    request.getId(),
                    "ACTIVE")
                .size();
            String nextStatus = deriveStatus(request, activeAllocations);
            request.setStatus(nextStatus);
            purchaseRequestRepository.save(request);
            statuses.put(request.getId(), nextStatus);
        }
        return statuses;
    }

    private String deriveStatus(PurchaseRequest request, long activeAllocations) {
        if (activeAllocations == 0) {
            return PurchaseRequestStatus.APPROVED.name();
        }
        if (request.getRequestType() != RequestType.GOODS) {
            return PurchaseRequestStatus.RFQ_CREATED.name();
        }
        long unallocatedEligibleLines = purchaseRequestItemRepository.countEligibleUnallocatedMaterialLines(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            request.getId());
        return unallocatedEligibleLines > 0
            ? PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name()
            : PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name();
    }
}

package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseOrderDemandCyclePolicy {

    public static final String MESSAGE_KEY = "procurement.demand_cycle_approval_required";
    public static final String BLOCKED_REASON =
        "Linked demand planning cycle must be BUDGET_APPROVED or ACTIVE before purchase order creation";

    private static final Set<String> APPROVED_STATUSES = Set.of(
        DemandPlanningCycleStatus.BUDGET_APPROVED.name(),
        DemandPlanningCycleStatus.ACTIVE.name()
    );

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final DemandPlanRepository demandPlanRepository;
    private final ContractRepository contractRepository;
    private final RFQRepository rfqRepository;
    private final RFQPurchaseRequestSourceRepository rfqSourceRepository;

    @Transactional(readOnly = true)
    public Readiness readinessForPurchaseRequest(UUID purchaseRequestId) {
        if (purchaseRequestId == null) {
            return Readiness.blocked();
        }
        PurchaseRequest purchaseRequest = purchaseRequestRepository
            .findByIdAndIsDeletedFalse(purchaseRequestId)
            .filter(this::isInCurrentScope)
            .orElse(null);
        if (purchaseRequest == null || purchaseRequest.getDemandPlanId() == null) {
            return Readiness.blocked();
        }
        DemandPlan demandPlan = demandPlanRepository
            .findByIdAndIsDeletedFalse(purchaseRequest.getDemandPlanId())
            .filter(this::isInCurrentScope)
            .orElse(null);
        if (demandPlan == null) {
            return Readiness.blocked();
        }
        DemandPlanningCycle cycle = demandPlan.getPlanningCycle();
        if (!isInCurrentScope(cycle) || !APPROVED_STATUSES.contains(cycle.getStatus())) {
            return Readiness.blocked();
        }
        return Readiness.allowed();
    }

    @Transactional(readOnly = true)
    public Readiness readinessForContract(UUID contractId) {
        if (contractId == null) {
            return Readiness.blocked();
        }
        Contract contract = contractRepository
            .findByIdAndIsDeletedFalse(contractId)
            .filter(this::isInCurrentScope)
            .orElse(null);
        if (contract == null || contract.getSourceRfqId() == null) {
            return Readiness.blocked();
        }
        RFQ rfq = rfqRepository
            .findByIdAndIsDeletedFalse(contract.getSourceRfqId())
            .filter(this::isInCurrentScope)
            .orElse(null);
        if (rfq == null) {
            return Readiness.blocked();
        }
        Readiness directReadiness = readinessForPurchaseRequest(rfq.getPurchaseRequestId());
        if (directReadiness.ready()) {
            return directReadiness;
        }
        List<UUID> purchaseRequestIds = rfqSourceRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfq.getId(),
                "ACTIVE"
            )
            .stream()
            .map(RFQPurchaseRequestSource::getPurchaseRequestId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (purchaseRequestIds.isEmpty()) {
            return directReadiness;
        }
        return purchaseRequestIds.stream()
            .map(this::readinessForPurchaseRequest)
            .filter(Readiness::ready)
            .findFirst()
            .orElse(directReadiness);
    }

    @Transactional(readOnly = true)
    public void requireApprovedCycleForPurchaseRequest(UUID purchaseRequestId) {
        requireReady(readinessForPurchaseRequest(purchaseRequestId), purchaseRequestId);
    }

    @Transactional(readOnly = true)
    public void requireApprovedCycleForContract(UUID contractId) {
        requireReady(readinessForContract(contractId), contractId);
    }

    private void requireReady(Readiness readiness, UUID sourceId) {
        if (!readiness.ready()) {
            throw new BusinessException(BLOCKED_REASON, MESSAGE_KEY, sourceId);
        }
    }

    private boolean isInCurrentScope(BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && Objects.equals(TenantContext.currentTenant(), entity.getTenantId())
            && Objects.equals(CompanyContext.currentCompany(), entity.getCompanyId());
    }

    public record Readiness(boolean ready, String reason) {
        private static Readiness allowed() {
            return new Readiness(true, null);
        }

        private static Readiness blocked() {
            return new Readiness(false, BLOCKED_REASON);
        }
    }
}

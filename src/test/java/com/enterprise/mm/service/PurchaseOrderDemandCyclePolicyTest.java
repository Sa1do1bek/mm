package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderDemandCyclePolicyTest {

    private static final String TENANT_ID = "default";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private DemandPlanRepository demandPlanRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private RFQRepository rfqRepository;
    @Mock private RFQPurchaseRequestSourceRepository rfqSourceRepository;

    private PurchaseOrderDemandCyclePolicy policy;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
        policy = new PurchaseOrderDemandCyclePolicy(
            purchaseRequestRepository,
            demandPlanRepository,
            contractRepository,
            rfqRepository,
            rfqSourceRepository
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @EnumSource(
        value = DemandPlanningCycleStatus.class,
        names = {"BUDGET_APPROVED", "ACTIVE"}
    )
    void purchaseRequestIsReadyOnlyForApprovedCycleStatuses(DemandPlanningCycleStatus status) {
        UUID purchaseRequestId = stubPurchaseRequestLineage(status);

        PurchaseOrderDemandCyclePolicy.Readiness readiness =
            policy.readinessForPurchaseRequest(purchaseRequestId);

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.reason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
        value = DemandPlanningCycleStatus.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = {"BUDGET_APPROVED", "ACTIVE"}
    )
    void purchaseRequestIsBlockedForEveryOtherCycleStatus(DemandPlanningCycleStatus status) {
        UUID purchaseRequestId = stubPurchaseRequestLineage(status);

        PurchaseOrderDemandCyclePolicy.Readiness readiness =
            policy.readinessForPurchaseRequest(purchaseRequestId);

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.reason()).isEqualTo(PurchaseOrderDemandCyclePolicy.BLOCKED_REASON);
        assertThatThrownBy(() -> policy.requireApprovedCycleForPurchaseRequest(purchaseRequestId))
            .isInstanceOf(BusinessException.class)
            .hasMessage(PurchaseOrderDemandCyclePolicy.BLOCKED_REASON)
            .extracting(error -> ((BusinessException) error).getMessageKey())
            .isEqualTo(PurchaseOrderDemandCyclePolicy.MESSAGE_KEY);
    }

    @Test
    void purchaseRequestWithoutDemandPlanLineageIsBlocked() {
        UUID purchaseRequestId = UUID.randomUUID();
        PurchaseRequest purchaseRequest = scoped(new PurchaseRequest(), purchaseRequestId);
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId))
            .thenReturn(Optional.of(purchaseRequest));

        assertThat(policy.readinessForPurchaseRequest(purchaseRequestId).ready()).isFalse();
    }

    @Test
    void contractUsesItsRfqPurchaseRequestLineage() {
        UUID purchaseRequestId = stubPurchaseRequestLineage(DemandPlanningCycleStatus.ACTIVE);
        UUID rfqId = UUID.randomUUID();
        RFQ rfq = scoped(new RFQ(), rfqId);
        rfq.setPurchaseRequestId(purchaseRequestId);
        when(rfqRepository.findByIdAndIsDeletedFalse(rfqId)).thenReturn(Optional.of(rfq));

        UUID contractId = UUID.randomUUID();
        Contract contract = scoped(new Contract(), contractId);
        contract.setSourceRfqId(rfqId);
        when(contractRepository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));

        assertThat(policy.readinessForContract(contractId).ready()).isTrue();
    }

    @Test
    void contractFallsBackToRfqSourceAllocationsForAggregatedRfqLineage() {
        UUID directPurchaseRequestId = UUID.randomUUID();
        RFQ rfq = scoped(new RFQ(), UUID.randomUUID());
        rfq.setPurchaseRequestId(directPurchaseRequestId);
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(directPurchaseRequestId))
            .thenReturn(Optional.empty());
        when(rfqRepository.findByIdAndIsDeletedFalse(rfq.getId())).thenReturn(Optional.of(rfq));

        UUID sourcePurchaseRequestId = stubPurchaseRequestLineage(DemandPlanningCycleStatus.BUDGET_APPROVED);
        RFQPurchaseRequestSource source = scoped(new RFQPurchaseRequestSource(), UUID.randomUUID());
        source.setRfqId(rfq.getId());
        source.setPurchaseRequestId(sourcePurchaseRequestId);
        when(rfqSourceRepository.findByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
            TENANT_ID,
            COMPANY_ID,
            rfq.getId(),
            "ACTIVE"
        )).thenReturn(List.of(source));

        Contract contract = scoped(new Contract(), UUID.randomUUID());
        contract.setSourceRfqId(rfq.getId());
        when(contractRepository.findByIdAndIsDeletedFalse(contract.getId())).thenReturn(Optional.of(contract));

        assertThat(policy.readinessForContract(contract.getId()).ready()).isTrue();
    }

    private UUID stubPurchaseRequestLineage(DemandPlanningCycleStatus status) {
        UUID cycleId = UUID.randomUUID();
        DemandPlanningCycle cycle = scoped(new DemandPlanningCycle(), cycleId);
        cycle.setStatus(status.name());

        UUID demandPlanId = UUID.randomUUID();
        DemandPlan demandPlan = scoped(new DemandPlan(), demandPlanId);
        demandPlan.setPlanningCycle(cycle);
        when(demandPlanRepository.findByIdAndIsDeletedFalse(demandPlanId))
            .thenReturn(Optional.of(demandPlan));

        UUID purchaseRequestId = UUID.randomUUID();
        PurchaseRequest purchaseRequest = scoped(new PurchaseRequest(), purchaseRequestId);
        purchaseRequest.setDemandPlanId(demandPlanId);
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId))
            .thenReturn(Optional.of(purchaseRequest));
        return purchaseRequestId;
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T scoped(T entity, UUID id) {
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setCompanyId(COMPANY_ID);
        entity.setDeleted(false);
        return entity;
    }
}

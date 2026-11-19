package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ProcurementProcedureMapper;
import com.enterprise.mm.payload.ProcurementProcedureCancelRequest;
import com.enterprise.mm.payload.ProcurementMethodCapabilityDtos;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.enums.ProcurementMethodCapabilityState;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementProcedureServiceTest {
    private static final UUID DECISION_ID = UUID.randomUUID();
    private static final UUID PROCEDURE_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    @Mock private ProcurementProcedureRepository repository;
    @Mock private ProcurementProcedureMapper mapper;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private PurchaseOrderDemandCyclePolicy demandCyclePolicy;
    @Mock private ProcurementMethodCapabilityService capabilityService;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private ProcurementMethodDecisionRepository methodDecisionRepository;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Spy private ProcurementMethodPolicy methodPolicy = new ProcurementMethodPolicy();
    @InjectMocks private ProcurementProcedureService service;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createsOnlyOneProcedureForApprovedDecision() {
        ProcurementMethodDecision decision = decision("APPROVED", "OPEN_TENDER");
        stubAvailableCapability(decision);
        when(repository.findByMethodDecisionIdAndIsDeletedFalse(DECISION_ID))
            .thenReturn(Optional.empty())
            .thenAnswer(invocation -> Optional.of(procedure("DRAFT", "OPEN_TENDER")));
        when(repository.save(any(ProcurementProcedure.class))).thenAnswer(invocation -> {
            ProcurementProcedure saved = invocation.getArgument(0);
            saved.setId(PROCEDURE_ID);
            return saved;
        });

        service.createForApprovedDecision(decision);
        service.createForApprovedDecision(decision);

        verify(repository, times(1)).save(any(ProcurementProcedure.class));
        ArgumentCaptor<ProcurementProcedure> captor = ArgumentCaptor.forClass(ProcurementProcedure.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMethod()).isEqualTo("OPEN_TENDER");
        assertThat(captor.getValue().getMethodDecisionId()).isEqualTo(DECISION_ID);
        assertThat(LocalizedText.translate(captor.getValue().getName(), Locale.ENGLISH))
            .isEqualTo("Procurement Procedure - Chemical Procurement for Production");
    }

    @Test
    void lifecycleMovesReadyLaunchedAndCompleted() {
        ProcurementProcedure procedure = procedure("DRAFT", "OPEN_TENDER");
        stubExisting(procedure);
        stubApprovedDecision("OPEN_TENDER");
        when(repository.save(procedure)).thenReturn(procedure);

        service.ready(PROCEDURE_ID);
        assertThat(procedure.getStatus()).isEqualTo("READY");
        service.linkCampaign(PROCEDURE_ID, CAMPAIGN_ID);
        service.launch(PROCEDURE_ID);
        assertThat(procedure.getStatus()).isEqualTo("LAUNCHED");
        assertThat(procedure.getLaunchedAt()).isNotNull();
        service.complete(PROCEDURE_ID);
        assertThat(procedure.getStatus()).isEqualTo("COMPLETED");
        assertThat(procedure.getCompletedAt()).isNotNull();
    }

    @Test
    void auctionMethodLaunchesIntoExecutionDraft() {
        ProcurementProcedure procedure = procedure("READY", "E_AUCTION");
        stubExisting(procedure);
        stubApprovedDecision("E_AUCTION");
        when(repository.save(procedure)).thenReturn(procedure);

        service.launch(PROCEDURE_ID);

        assertThat(procedure.getStatus()).isEqualTo("AUCTION_DRAFT");
    }

    @Test
    void directAwardMethodLaunchesWithoutCampaign() {
        ProcurementProcedure procedure = procedure("READY", "SINGLE_SOURCE");
        procedure.setCampaignId(null);
        stubExisting(procedure);
        stubApprovedDecision("SINGLE_SOURCE");
        when(repository.save(procedure)).thenReturn(procedure);

        service.launch(PROCEDURE_ID);

        assertThat(procedure.getStatus()).isEqualTo("DIRECT_AWARD_DRAFT");
    }

    @Test
    void auctionWorkflowCanPublishStartAndClose() {
        ProcurementProcedure procedure = procedure("AUCTION_DRAFT", "E_AUCTION");
        stubExisting(procedure);
        when(repository.save(procedure)).thenReturn(procedure);

        service.publishAuction(PROCEDURE_ID);
        assertThat(procedure.getStatus()).isEqualTo("AUCTION_PUBLISHED");

        service.startAuction(PROCEDURE_ID);
        assertThat(procedure.getStatus()).isEqualTo("AUCTION_RUNNING");

        service.closeAuction(PROCEDURE_ID);
        assertThat(procedure.getStatus()).isEqualTo("AUCTION_CLOSED");
    }

    @Test
    void frameworkWorkflowCanAwardActivateAndComplete() {
        ProcurementProcedure procedure = procedure("FRAMEWORK_DRAFT", "FRAME_AGREEMENT");
        stubExisting(procedure);
        when(repository.save(procedure)).thenReturn(procedure);

        service.awardFramework(PROCEDURE_ID);
        assertThat(procedure.getStatus()).isEqualTo("FRAMEWORK_AWARDED");
        service.activateFramework(PROCEDURE_ID);
        assertThat(procedure.getStatus()).isEqualTo("FRAMEWORK_ACTIVE");
        service.complete(PROCEDURE_ID);
        assertThat(procedure.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void directAwardProcedureCannotUseGenericCompletionBeforePoCreation() {
        ProcurementProcedure procedure = procedure("DIRECT_AWARD_APPROVED", "DIRECT_PURCHASE");
        stubExisting(procedure);

        assertThatThrownBy(() -> service.complete(PROCEDURE_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not ready to complete");
    }

    @Test
    void rfqProcedureRequiresLinkedCampaignBeforeLaunch() {
        ProcurementProcedure procedure = procedure("READY", "OPEN_TENDER");
        procedure.setCampaignId(null);
        stubExisting(procedure);
        stubApprovedDecision("OPEN_TENDER");

        assertThatThrownBy(() -> service.launch(PROCEDURE_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("campaignId");
    }

    @Test
    void cancellationRequiresReason() {
        ProcurementProcedure procedure = procedure("READY", "OPEN_TENDER");
        stubExisting(procedure);

        assertThatThrownBy(() -> service.cancel(PROCEDURE_ID, new ProcurementProcedureCancelRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cancellationReason");
    }

    private void stubExisting(ProcurementProcedure procedure) {
        when(repository.findByIdAndIsDeletedFalse(PROCEDURE_ID)).thenReturn(Optional.of(procedure));
    }

    private void stubApprovedDecision(String method) {
        ProcurementMethodDecision decision = decision("APPROVED", method);
        when(methodDecisionRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
            DECISION_ID, "default", CompanyContext.DEFAULT_COMPANY_ID))
            .thenReturn(Optional.of(decision));
        stubAvailableCapability(decision);
    }

    private void stubAvailableCapability(ProcurementMethodDecision decision) {
        when(purchaseRequestItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
            org.mockito.ArgumentMatchers.eq("default"),
            org.mockito.ArgumentMatchers.eq(CompanyContext.DEFAULT_COMPANY_ID),
            org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(java.util.List.of());
        when(capabilityService.evaluateForCompany(
            org.mockito.ArgumentMatchers.eq(decision.getSelectedMethod()),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(decision.getCompanyId())))
            .thenReturn(new ProcurementMethodCapabilityDtos.Evaluation(
                decision.getSelectedMethod(),
                true,
                ProcurementMethodCapabilityState.ENABLED,
                java.util.List.of(),
                new ProcurementMethodCapabilityDtos.FrontendMetadata(true, false, false, false, false, false, true, 1)
            ));
    }

    private ProcurementMethodDecision decision(String status, String method) {
        ProcurementMethodDecision decision = new ProcurementMethodDecision();
        decision.setId(DECISION_ID);
        decision.setTenantId("default");
        decision.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        decision.setCode("PMD-1");
        decision.setName("Procurement Method Decision - Chemical Procurement for Production");
        decision.setDecisionNumber("PMD-1");
        decision.setStatus(status);
        decision.setSelectedMethod(method);
        decision.setPurchaseRequestId(UUID.randomUUID());
        return decision;
    }

    private ProcurementProcedure procedure(String status, String method) {
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setId(PROCEDURE_ID);
        procedure.setTenantId("default");
        procedure.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        procedure.setCode("PROC-1");
        procedure.setName("Procurement procedure");
        procedure.setProcedureNumber("PROC-1");
        procedure.setMethodDecisionId(DECISION_ID);
        procedure.setProcurementPlanId(PLAN_ID);
        procedure.setMethod(method);
        procedure.setExecutionPath(methodPolicy.require(method).executionPath().name());
        procedure.setStatus(status);
        return procedure;
    }
}

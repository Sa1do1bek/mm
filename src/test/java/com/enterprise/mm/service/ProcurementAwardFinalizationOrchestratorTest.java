package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementCampaignStatus;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.service.ProcurementAwardFinalizationOrchestrator.AwardFinalizationCommand;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcurementAwardFinalizationOrchestratorTest {
    private static final String TENANT_ID = "default";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;
    private static final UUID PROCEDURE_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID SUPPLIER_ID = UUID.randomUUID();
    private static final UUID PURCHASE_ORDER_ID = UUID.randomUUID();
    private static final UUID CONTRACT_ID = UUID.randomUUID();
    private static final UUID OUTCOME_ID = UUID.randomUUID();

    @Mock private ProcurementAwardOutcomeRepository outcomeRepository;
    @Mock private ProcurementProcedureRepository procedureRepository;
    @Mock private ProcurementCampaignRepository campaignRepository;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private ProcurementAwardOutcomeLineService outcomeLineService;
    @InjectMocks private ProcurementAwardFinalizationOrchestrator service;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createsOutcomeAndNormalizesProcedureAndCampaign() {
        ProcurementProcedure procedure = procedure("AUCTION_CLOSED");
        procedure.setCampaignId(CAMPAIGN_ID);
        ProcurementCampaign campaign = campaign();
        when(procedureRepository.lockScopedById(TENANT_ID, COMPANY_ID, PROCEDURE_ID))
            .thenReturn(Optional.of(procedure));
        when(outcomeRepository
            .findByTenantIdAndCompanyIdAndProcurementProcedureIdAndSourceTypeAndIdempotencyKeyAndIsDeletedFalse(
                TENANT_ID,
                COMPANY_ID,
                PROCEDURE_ID,
                ProcurementAwardSourceType.MANAGED_AUCTION,
                PURCHASE_ORDER_ID.toString()
            )).thenReturn(Optional.empty());
        when(outcomeRepository.save(any(ProcurementAwardOutcome.class))).thenAnswer(invocation -> {
            ProcurementAwardOutcome outcome = invocation.getArgument(0);
            outcome.setId(OUTCOME_ID);
            return outcome;
        });
        when(campaignRepository.findByIdAndIsDeletedFalse(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        ProcurementAwardOutcome result = service.finalizeAward(command(
            ProcurementAwardSourceType.MANAGED_AUCTION,
            PURCHASE_ORDER_ID,
            PURCHASE_ORDER_ID,
            null,
            ProcurementProcedureStatus.COMPLETED,
            "PROCUREMENT.AUCTION_AWARDED",
            "AUCTION_AWARD"
        ));

        assertThat(result.getId()).isEqualTo(OUTCOME_ID);
        assertThat(procedure.getStatus()).isEqualTo("COMPLETED");
        assertThat(procedure.getAwardedSupplierId()).isEqualTo(SUPPLIER_ID);
        assertThat(procedure.getPurchaseOrderId()).isEqualTo(PURCHASE_ORDER_ID);
        assertThat(procedure.getFinalizedAmount()).isEqualByComparingTo("460");
        assertThat(procedure.getFinalizedCurrencyCode()).isEqualTo("UZS");
        assertThat(procedure.getAwardReference()).isEqualTo("EA-1");
        assertThat(procedure.getCompletedAt()).isNotNull();
        assertThat(campaign.getStatus()).isEqualTo(ProcurementCampaignStatus.COMPLETED.name());
        assertThat(campaign.getCompletedAt()).isNotNull();

        ArgumentCaptor<ProcurementAwardOutcome> outcomeCaptor =
            ArgumentCaptor.forClass(ProcurementAwardOutcome.class);
        verify(outcomeRepository).save(outcomeCaptor.capture());
        ProcurementAwardOutcome saved = outcomeCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("FINALIZED");
        assertThat(saved.getSourceType()).isEqualTo(ProcurementAwardSourceType.MANAGED_AUCTION);
        assertThat(saved.getIdempotencyKey()).isEqualTo(PURCHASE_ORDER_ID.toString());
        assertThat(saved.getCurrencyCode()).isEqualTo("UZS");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(integrationEventService).emitIdempotentInCurrentTransaction(
            eq("PROCUREMENT.AUCTION_AWARDED"),
            eq("ProcurementProcedure"),
            eq(PROCEDURE_ID),
            payloadCaptor.capture(),
            eq("AWARD_OUTCOME:" + OUTCOME_ID + ":PROCUREMENT.AUCTION_AWARDED")
        );
        assertThat(payloadCaptor.getValue()).containsEntry("purchaseOrderId", PURCHASE_ORDER_ID);
        verify(auditService).log("ProcurementProcedure", PROCEDURE_ID, "AUCTION_AWARD", "EA-1", null, result);
        verify(outcomeLineService).recordLines(result);
    }

    @Test
    void replaysExistingOutcomeWithoutDuplicateSideEffects() {
        ProcurementProcedure procedure = procedure("AUCTION_CLOSED");
        ProcurementAwardOutcome existing = outcome(
            ProcurementAwardSourceType.MANAGED_AUCTION,
            PURCHASE_ORDER_ID,
            PURCHASE_ORDER_ID,
            null
        );
        when(procedureRepository.lockScopedById(TENANT_ID, COMPANY_ID, PROCEDURE_ID))
            .thenReturn(Optional.of(procedure));
        when(outcomeRepository
            .findByTenantIdAndCompanyIdAndProcurementProcedureIdAndSourceTypeAndIdempotencyKeyAndIsDeletedFalse(
                TENANT_ID,
                COMPANY_ID,
                PROCEDURE_ID,
                ProcurementAwardSourceType.MANAGED_AUCTION,
                PURCHASE_ORDER_ID.toString()
            )).thenReturn(Optional.of(existing));

        ProcurementAwardOutcome result = service.finalizeAward(command(
            ProcurementAwardSourceType.MANAGED_AUCTION,
            PURCHASE_ORDER_ID,
            PURCHASE_ORDER_ID,
            null,
            ProcurementProcedureStatus.COMPLETED,
            "PROCUREMENT.AUCTION_AWARDED",
            "AUCTION_AWARD"
        ));

        assertThat(result).isSameAs(existing);
        assertThat(procedure.getStatus()).isEqualTo("COMPLETED");
        assertThat(procedure.getPurchaseOrderId()).isEqualTo(PURCHASE_ORDER_ID);
        verify(outcomeRepository, never()).save(any());
        verify(integrationEventService, never())
            .emitIdempotentInCurrentTransaction(any(), any(), any(), anyMap(), any());
        verify(auditService, never()).log(any(), any(), any(), any(), any(), any());
        verify(outcomeLineService).recordLines(existing);
    }

    @Test
    void rejectsConflictingPurchaseOrderBeforeWritingOutcome() {
        UUID existingPurchaseOrderId = UUID.randomUUID();
        ProcurementProcedure procedure = procedure("COMPLETED");
        procedure.setPurchaseOrderId(existingPurchaseOrderId);
        when(procedureRepository.lockScopedById(TENANT_ID, COMPANY_ID, PROCEDURE_ID))
            .thenReturn(Optional.of(procedure));

        assertThatThrownBy(() -> service.finalizeAward(command(
            ProcurementAwardSourceType.MANAGED_AUCTION,
            PURCHASE_ORDER_ID,
            PURCHASE_ORDER_ID,
            null,
            ProcurementProcedureStatus.COMPLETED,
            null,
            null
        ))).isInstanceOf(BusinessException.class)
            .hasMessageContaining("purchaseOrderId");

        verifyNoInteractions(outcomeRepository);
    }

    @Test
    void rejectsContractAwardWithoutContractBeforeLockingProcedure() {
        assertThatThrownBy(() -> service.finalizeAward(command(
            ProcurementAwardSourceType.RFQ_CONTRACT,
            UUID.randomUUID(),
            null,
            null,
            ProcurementProcedureStatus.COMPLETED,
            null,
            null
        ))).isInstanceOf(BusinessException.class)
            .hasMessageContaining("contractId is required");

        verifyNoInteractions(procedureRepository, outcomeRepository, integrationEventService);
    }

    @Test
    void rejectsPurchaseOrderAwardWithoutPurchaseOrderBeforeLockingProcedure() {
        assertThatThrownBy(() -> service.finalizeAward(command(
            ProcurementAwardSourceType.DIRECT_AWARD,
            UUID.randomUUID(),
            null,
            CONTRACT_ID,
            ProcurementProcedureStatus.COMPLETED,
            null,
            null
        ))).isInstanceOf(BusinessException.class)
            .hasMessageContaining("purchaseOrderId is required");

        verifyNoInteractions(procedureRepository, outcomeRepository, integrationEventService);
    }

    @Test
    void rejectsFrameworkCallOffWithoutEitherCommercialDocumentBeforeLockingProcedure() {
        assertThatThrownBy(() -> service.finalizeAward(command(
            ProcurementAwardSourceType.FRAMEWORK_CALLOFF,
            UUID.randomUUID(),
            PURCHASE_ORDER_ID,
            null,
            ProcurementProcedureStatus.COMPLETED,
            null,
            null
        ))).isInstanceOf(BusinessException.class)
            .hasMessageContaining("contractId is required");

        verifyNoInteractions(procedureRepository, outcomeRepository, integrationEventService);
    }

    private AwardFinalizationCommand command(
        ProcurementAwardSourceType sourceType,
        UUID sourceId,
        UUID purchaseOrderId,
        UUID contractId,
        ProcurementProcedureStatus status,
        String eventType,
        String auditAction
    ) {
        return new AwardFinalizationCommand(
            PROCEDURE_ID,
            sourceType,
            sourceId,
            SUPPLIER_ID,
            purchaseOrderId,
            contractId,
            BigDecimal.valueOf(460),
            "uzs",
            "EA-1",
            status,
            eventType,
            auditAction
        );
    }

    private ProcurementProcedure procedure(String status) {
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setId(PROCEDURE_ID);
        procedure.setTenantId(TENANT_ID);
        procedure.setCompanyId(COMPANY_ID);
        procedure.setCode("PROC-1");
        procedure.setName("Procurement procedure");
        procedure.setStatus(status);
        procedure.setProcedureNumber("PROC-1");
        return procedure;
    }

    private ProcurementCampaign campaign() {
        ProcurementCampaign campaign = new ProcurementCampaign();
        campaign.setId(CAMPAIGN_ID);
        campaign.setTenantId(TENANT_ID);
        campaign.setCompanyId(COMPANY_ID);
        campaign.setCode("PC-1");
        campaign.setName("Procurement campaign");
        campaign.setStatus("RFQ_CREATED");
        return campaign;
    }

    private ProcurementAwardOutcome outcome(
        ProcurementAwardSourceType sourceType,
        UUID sourceId,
        UUID purchaseOrderId,
        UUID contractId
    ) {
        ProcurementAwardOutcome outcome = new ProcurementAwardOutcome();
        outcome.setId(OUTCOME_ID);
        outcome.setTenantId(TENANT_ID);
        outcome.setCompanyId(COMPANY_ID);
        outcome.setCode("PAO-1");
        outcome.setName("Award outcome");
        outcome.setStatus("FINALIZED");
        outcome.setProcurementProcedureId(PROCEDURE_ID);
        outcome.setSourceType(sourceType);
        outcome.setSourceId(sourceId);
        outcome.setAwardedSupplierId(SUPPLIER_ID);
        outcome.setPurchaseOrderId(purchaseOrderId);
        outcome.setContractId(contractId);
        outcome.setAwardAmount(BigDecimal.valueOf(460));
        outcome.setCurrencyCode("UZS");
        outcome.setAwardReference("EA-1");
        outcome.setIdempotencyKey(sourceId.toString());
        return outcome;
    }
}

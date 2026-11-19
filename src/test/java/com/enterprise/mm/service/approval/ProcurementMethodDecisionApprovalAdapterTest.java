package com.enterprise.mm.service.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.enums.ProcurementMethodCapabilityState;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ProcurementMethodCapabilityDtos;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.IntegrationEventService;
import com.enterprise.mm.service.ProcurementMethodCapabilityService;
import com.enterprise.mm.service.ProcurementMethodPolicy;
import com.enterprise.mm.service.ProcurementProcedureService;
import com.enterprise.mm.service.SegregationOfDutyRuleService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ProcurementMethodDecisionApprovalAdapterTest {
    private static final UUID DECISION_ID = UUID.randomUUID();
    private static final UUID MARKET_ATTACHMENT_ID = UUID.randomUUID();
    private static final UUID TECHNICAL_ATTACHMENT_ID = UUID.randomUUID();

    @Mock private ProcurementMethodDecisionRepository repository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private ProcurementProcedureService procedureService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private SegregationOfDutyRuleService segregationOfDutyRuleService;
    @Mock private ProcurementMethodCapabilityService capabilityService;
    @Spy private ProcurementMethodPolicy methodPolicy = new ProcurementMethodPolicy();
    @InjectMocks private ProcurementMethodDecisionApprovalAdapter adapter;

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("procurement-user", "n/a")
        );
        lenient().when(purchaseRequestItemRepository.findByTenantIdAndCompanyIdAndPurchaseRequestIdInAndIsDeletedFalse(
            any(), any(), any()
        )).thenReturn(List.of());
        lenient().when(capabilityService.requireAvailableForCompany(any(), any(), any(), any(), any(), any()))
            .thenReturn(capabilityEvaluation());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void validateCanSubmitRequiresMarketResearchEvidence() {
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "DRAFT", 3);
        entity.setMarketResearchAttachmentId(null);
        stubExisting(entity);

        assertThatThrownBy(() -> adapter.validateCanSubmit(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("marketResearchAttachmentId");
    }

    @Test
    void validateCanSubmitRequiresSupplierDepthForCompetitiveMethod() {
        ProcurementMethodDecision entity = decision("CLOSED_TENDER", "DRAFT", 1);
        stubExisting(entity);
        stubEvidence();

        assertThatThrownBy(() -> adapter.validateCanSubmit(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("at least two potential suppliers");
    }

    @Test
    void validateCanSubmitDoesNotRequireSupplierDepthForOpenCompetition() {
        ProcurementMethodDecision entity = decision("OPEN_COMPETITION", "DRAFT", 0);
        stubExisting(entity);
        stubEvidence();

        adapter.validateCanSubmit(DECISION_ID);
    }

    @Test
    void validateCanSubmitRequiresExceptionReasonForSingleSource() {
        ProcurementMethodDecision entity = decision("SINGLE_SOURCE", "DRAFT", 1);
        entity.setExceptionReason(null);
        stubExisting(entity);
        stubEvidence();

        assertThatThrownBy(() -> adapter.validateCanSubmit(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("exceptionReason");
    }

    @Test
    void validateCanSubmitRequiresJustificationForSingleSource() {
        ProcurementMethodDecision entity = decision("SINGLE_SOURCE", "DRAFT", 1);
        entity.setJustification(null);
        stubExisting(entity);
        stubEvidence();

        assertThatThrownBy(() -> adapter.validateCanSubmit(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("justification must contain a meaningful explanation");
    }

    @Test
    void validateCanSubmitRequiresMeaningfulJustificationForSingleSource() {
        ProcurementMethodDecision entity = decision("SINGLE_SOURCE", "DRAFT", 1);
        entity.setJustification("   short   ");
        stubExisting(entity);
        stubEvidence();

        assertThatThrownBy(() -> adapter.validateCanSubmit(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("justification must contain a meaningful explanation");
    }

    @Test
    void validateCanSubmitRequiresJustificationForDirectPurchase() {
        ProcurementMethodDecision entity = decision("DIRECT_PURCHASE", "DRAFT", 1);
        entity.setJustification("   ");
        stubExisting(entity);
        stubEvidence();

        assertThatThrownBy(() -> adapter.validateCanSubmit(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("justification must contain a meaningful explanation");
    }

    @Test
    void validateCanSubmitRequiresExactlyOneSupplierForSingleSource() {
        ProcurementMethodDecision entity = decision("SINGLE_SOURCE", "DRAFT", 2);
        stubExisting(entity);
        stubEvidence();

        assertThatThrownBy(() -> adapter.validateCanSubmit(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("exactly one potential supplier");
    }

    @Test
    void validateCanSubmitAllowsMissingJustification() {
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "DRAFT", 3);
        entity.setJustification(null);
        stubExisting(entity);
        stubEvidence();

        adapter.validateCanSubmit(DECISION_ID);
    }

    @Test
    void markSubmittedAndApprovedCreateProcedureThroughUnifiedApproval() {
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "DRAFT", 3);
        stubExisting(entity);
        stubEvidence();
        when(repository.save(any(ProcurementMethodDecision.class))).thenAnswer(invocation -> invocation.getArgument(0));

        adapter.validateCanSubmit(DECISION_ID);
        adapter.markSubmitted(DECISION_ID);
        adapter.markApproved(DECISION_ID, "Approved");

        assertThat(entity.getStatus()).isEqualTo("APPROVED");
        assertThat(entity.getSubmittedAt()).isNotNull();
        assertThat(entity.getApprovedAt()).isNotNull();
        assertThat(entity.getApprovedBy()).isEqualTo("procurement-user");
        verify(procedureService).createForApprovedDecision(entity);
    }

    @Test
    void markRejectedRequiresComment() {
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "UNDER_REVIEW", 3);
        stubExisting(entity);

        assertThatThrownBy(() -> adapter.markRejected(DECISION_ID, " "))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("rejectionReason");
    }

    private ProcurementMethodDecision decision(String method, String status, int suppliers) {
        ProcurementMethodDecision entity = new ProcurementMethodDecision();
        entity.setId(DECISION_ID);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setCode("PMD-1");
        entity.setName("Method decision");
        entity.setDecisionNumber("PMD-1");
        entity.setSelectedMethod(method);
        entity.setStatus(status);
        entity.setJustification("Market supports selected procedure");
        entity.setEstimatedAmount(BigDecimal.valueOf(10_000));
        entity.setCurrencyCode("UZS");
        entity.setPotentialSupplierCount(suppliers);
        entity.setMarketResearchAttachmentId(MARKET_ATTACHMENT_ID);
        entity.setTechnicalSpecificationAttachmentId(TECHNICAL_ATTACHMENT_ID);
        entity.setExceptionReason("Urgent single-source justification");
        return entity;
    }

    private void stubExisting(ProcurementMethodDecision entity) {
        when(repository.findByIdAndIsDeletedFalse(DECISION_ID)).thenReturn(Optional.of(entity));
    }

    private void stubEvidence() {
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, MARKET_ATTACHMENT_ID
        )).thenReturn(Optional.of(attachment(MARKET_ATTACHMENT_ID)));
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, TECHNICAL_ATTACHMENT_ID
        )).thenReturn(Optional.of(attachment(TECHNICAL_ATTACHMENT_ID)));
    }

    private Attachment attachment(UUID id) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setTenantId("default");
        attachment.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        return attachment;
    }

    private ProcurementMethodCapabilityDtos.Evaluation capabilityEvaluation() {
        return new ProcurementMethodCapabilityDtos.Evaluation(
            "OPEN_TENDER",
            true,
            ProcurementMethodCapabilityState.ENABLED,
            List.of(),
            new ProcurementMethodCapabilityDtos.FrontendMetadata(
                true, false, false, false, false, false, true, 1
            )
        );
    }
}

package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ProcurementMethodDecisionMapper;
import com.enterprise.mm.payload.ProcurementMethodDecisionCreateRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionRejectRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionUpdateRequest;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ProcurementMethodDecisionApprovalAdapter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
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
class ProcurementMethodDecisionServiceTest {
    private static final UUID DECISION_ID = UUID.randomUUID();
    private static final UUID MARKET_ATTACHMENT_ID = UUID.randomUUID();
    private static final UUID TECHNICAL_ATTACHMENT_ID = UUID.randomUUID();
    private static final UUID COMMISSION_ID = UUID.randomUUID();

    @Mock private ProcurementMethodDecisionRepository repository;
    @Mock private ProcurementMethodDecisionMapper mapper;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private PurchaseOrderDemandCyclePolicy demandCyclePolicy;
    @Mock private CommissionRepository commissionRepository;
    @Mock private AuditService auditService;
    @Mock private ProcurementMethodCapabilityService capabilityService;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private ProcurementMethodDecisionActionPolicy actionPolicy;
    @Spy private ProcurementMethodPolicy methodPolicy = new ProcurementMethodPolicy();
    @InjectMocks private ProcurementMethodDecisionService service;

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
        lenient().when(commissionRepository.findByIdAndIsDeletedFalse(COMMISSION_ID))
            .thenReturn(Optional.of(commission(CommissionType.TENDER)));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createRejectsDecisionWithoutDemandPlanPurchaseRequest() {
        ProcurementMethodDecisionCreateRequest request = validRequest("OPEN_TENDER", 3);
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "CLOSED", 3);
        entity.setPurchaseRequestId(null);
        when(mapper.toEntity(request)).thenReturn(entity);
        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("approved demand-plan purchase request");
    }

    @Test
    void submitRejectsDecisionWithoutDemandPlanPurchaseRequest() {
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "DRAFT", 3);
        entity.setPurchaseRequestId(null);
        stubExisting(entity);

        assertThatThrownBy(() -> service.submit(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("approved demand-plan purchase request");
    }

    @Test
    void createRejectsCanonicalMethodWithoutDemandPlanPurchaseRequest() {
        ProcurementMethodDecisionCreateRequest request = validRequest("COMPETITIVE_TENDER", 3);
        ProcurementMethodDecision entity = decision("COMPETITIVE_TENDER", "CLOSED", 3);
        entity.setPurchaseRequestId(null);
        when(mapper.toEntity(request)).thenReturn(entity);
        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("approved demand-plan purchase request");
    }

    @Test
    void createReplacesSubmittedNameWithPurchaseRequestBusinessName() {
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementMethodDecisionCreateRequest request = validRequest("OPEN_TENDER", 3);
        request.setPurchaseRequestId(purchaseRequestId);
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "DRAFT", 3);
        entity.setPurchaseRequestId(purchaseRequestId);
        PurchaseRequest purchaseRequest = new PurchaseRequest();
        purchaseRequest.setId(purchaseRequestId);
        purchaseRequest.setTenantId("default");
        purchaseRequest.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseRequest.setName("Purchase Request - Chemical Procurement for Production");
        purchaseRequest.setStatus("APPROVED");
        when(mapper.toEntity(request)).thenReturn(entity);
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId))
            .thenReturn(Optional.of(purchaseRequest));
        when(repository.save(entity)).thenReturn(entity);

        service.create(request);

        assertThat(LocalizedText.translate(entity.getName(), Locale.ENGLISH))
            .isEqualTo("Procurement Method Decision - Chemical Procurement for Production");
    }

    @Test
    void createRequiresCompetitionCommissionForCompetitionMethods() {
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementMethodDecisionCreateRequest request = validRequest("OPEN_COMPETITION", 3);
        request.setPurchaseRequestId(purchaseRequestId);
        ProcurementMethodDecision entity = decision("OPEN_COMPETITION", "DRAFT", 3);
        entity.setPurchaseRequestId(purchaseRequestId);
        entity.setCommissionId(COMMISSION_ID);
        PurchaseRequest purchaseRequest = purchaseRequest(purchaseRequestId);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId))
            .thenReturn(Optional.of(purchaseRequest));
        when(commissionRepository.findByIdAndIsDeletedFalse(COMMISSION_ID))
            .thenReturn(Optional.of(commission(CommissionType.TENDER)));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active COMPETITION commission");
    }

    @Test
    void createAcceptsCompetitionCommissionForCompetitionMethods() {
        UUID purchaseRequestId = UUID.randomUUID();
        ProcurementMethodDecisionCreateRequest request = validRequest("OPEN_COMPETITION", 3);
        request.setPurchaseRequestId(purchaseRequestId);
        ProcurementMethodDecision entity = decision("OPEN_COMPETITION", "DRAFT", 3);
        entity.setPurchaseRequestId(purchaseRequestId);
        entity.setCommissionId(COMMISSION_ID);
        PurchaseRequest purchaseRequest = purchaseRequest(purchaseRequestId);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(purchaseRequestId))
            .thenReturn(Optional.of(purchaseRequest));
        when(commissionRepository.findByIdAndIsDeletedFalse(COMMISSION_ID))
            .thenReturn(Optional.of(commission(CommissionType.COMPETITION)));
        when(repository.save(entity)).thenReturn(entity);

        service.create(request);

        assertThat(entity.getCommissionId()).isEqualTo(COMMISSION_ID);
    }

    @Test
    void submitStartsUnifiedApproval() {
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "DRAFT", 3);
        stubExisting(entity);

        service.submit(DECISION_ID);

        verify(approvalEngineService).start(ProcurementMethodDecisionApprovalAdapter.MODULE_KEY, DECISION_ID);
    }

    @Test
    void directRejectEndpointRequiresApprovalTask() {
        ProcurementMethodDecisionRejectRequest request = new ProcurementMethodDecisionRejectRequest();

        assertThatThrownBy(() -> service.reject(DECISION_ID, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("approval task endpoints");
    }

    @Test
    void rejectedDecisionCanReturnToDraft() {
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "REJECTED", 3);
        entity.setRejectionReason("Clarify evidence");
        stubExisting(entity);
        when(repository.save(entity)).thenReturn(entity);

        service.revise(DECISION_ID);

        assertThat(entity.getStatus()).isEqualTo("DRAFT");
        assertThat(entity.getRejectionReason()).isNull();
        verify(approvalEngineService).resetForResubmission(
            ProcurementMethodDecisionApprovalAdapter.MODULE_KEY,
            DECISION_ID,
            "Procurement method decision revised for resubmission"
        );
    }

    @Test
    void directApprovalEndpointRequiresApprovalTask() {
        assertThatThrownBy(() -> service.approve(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("approval task endpoints");
    }

    @Test
    void approvedDecisionCannotBeUpdatedOrDeleted() {
        ProcurementMethodDecision entity = decision("OPEN_TENDER", "APPROVED", 3);
        stubExisting(entity);

        assertThatThrownBy(() -> service.update(DECISION_ID, new ProcurementMethodDecisionUpdateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Approved procurement method decision cannot be edited");
        assertThatThrownBy(() -> service.softDelete(DECISION_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be deleted");
    }

    private ProcurementMethodDecisionCreateRequest validRequest(String method, int suppliers) {
        ProcurementMethodDecisionCreateRequest request = new ProcurementMethodDecisionCreateRequest();
        request.setName("Method decision");
        request.setSelectedMethod(method);
        request.setJustification("Market supports selected procedure");
        request.setEstimatedAmount(BigDecimal.valueOf(10_000));
        request.setCurrencyCode("UZS");
        request.setPotentialSupplierCount(suppliers);
        request.setMarketResearchAttachmentId(MARKET_ATTACHMENT_ID);
        request.setTechnicalSpecificationAttachmentId(TECHNICAL_ATTACHMENT_ID);
        return request;
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
        entity.setPurchaseRequestId(UUID.randomUUID());
        if ("OPEN_TENDER".equals(method) || "CLOSED_TENDER".equals(method)) {
            entity.setCommissionId(COMMISSION_ID);
        }
        return entity;
    }

    private PurchaseRequest purchaseRequest(UUID id) {
        PurchaseRequest purchaseRequest = new PurchaseRequest();
        purchaseRequest.setId(id);
        purchaseRequest.setTenantId("default");
        purchaseRequest.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseRequest.setName("Purchase Request - Chemical Procurement for Production");
        purchaseRequest.setStatus("APPROVED");
        return purchaseRequest;
    }

    private Commission commission(CommissionType type) {
        Commission commission = new Commission();
        commission.setId(COMMISSION_ID);
        commission.setTenantId("default");
        commission.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        commission.setType(type);
        commission.setActive(true);
        return commission;
    }

    private void stubExisting(ProcurementMethodDecision entity) {
        when(repository.findByIdAndIsDeletedFalse(DECISION_ID)).thenReturn(Optional.of(entity));
    }

}

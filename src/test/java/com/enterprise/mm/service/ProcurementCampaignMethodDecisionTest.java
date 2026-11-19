package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementCampaign;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ProcurementProcedure;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.enums.RfqAggregationMode;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ProcurementCampaignMapper;
import com.enterprise.mm.payload.ProcurementCampaignCreateRequest;
import com.enterprise.mm.payload.ProcurementCampaignResponse;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateCampaignRfqRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ProcurementCampaignRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
class ProcurementCampaignMethodDecisionTest {
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID DECISION_ID = UUID.randomUUID();
    private static final UUID PROCEDURE_ID = UUID.randomUUID();
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID PURCHASE_REQUEST_ID = UUID.randomUUID();
    private static final UUID RFQ_ID = UUID.randomUUID();

    @Mock private ProcurementCampaignRepository repository;
    @Mock private ProcurementCampaignMapper mapper;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private RFQRepository rfqRepository;
    @Mock private RFQService rfqService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private ProcurementMethodDecisionRepository decisionRepository;
    @Mock private ProcurementProcedureRepository procedureRepository;
    @Mock private ProcurementProcedureService procedureService;
    @Mock private ProcurementCampaignRfqSourceService rfqSourceService;
    @Mock private RfqAggregationService rfqAggregationService;
    @Mock private PriceDiscoveryService priceDiscoveryService;
    @Mock private TenderCommissionService tenderCommissionService;
    @Spy private ProcurementMethodPolicy methodPolicy = new ProcurementMethodPolicy();
    @InjectMocks private ProcurementCampaignService service;

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
    void createDoesNotDeriveProcurementPlanFromApprovedProcedureBinding() {
        ProcurementCampaignCreateRequest request = request();
        ProcurementCampaign entity = campaign("DRAFT");
        entity.setProcurementPlanId(null);
        when(mapper.toEntity(request)).thenReturn(entity);
        stubDecisionAndProcedure("APPROVED", "OPEN_TENDER", "DRAFT");
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenAnswer(invocation -> response(entity));

        ProcurementCampaignResponse created = service.create(request);

        assertThat(entity.getProcurementPlanId()).isNull();
        assertThat(created).isNotNull();
        verify(procedureService).linkCampaign(PROCEDURE_ID, CAMPAIGN_ID);
    }

    @Test
    void createDerivesMethodFromApprovedProcedureAndIgnoresRequestOverride() {
        ProcurementCampaignCreateRequest request = request();
        ProcurementCampaign entity = campaign("DRAFT");
        entity.setProcedureType("SINGLE_SOURCE");
        when(mapper.toEntity(request)).thenReturn(entity);
        stubDecisionAndProcedure("APPROVED", "OPEN_TENDER", "DRAFT");
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenAnswer(invocation -> response(entity));

        ProcurementCampaignResponse created = service.create(request);

        assertThat(created.getProcedureType()).isEqualTo("OPEN_TENDER");
        assertThat(LocalizedText.translate(entity.getName(), Locale.ENGLISH))
            .isEqualTo("Procurement Campaign - Chemical Procurement for Production");
        verify(procedureService).linkCampaign(PROCEDURE_ID, CAMPAIGN_ID);
    }

    @Test
    void createPreservesEnteredTitleAndCampaignType() {
        ProcurementCampaignCreateRequest request = request();
        request.setName("Bearing 6205 ZZ RFQ");
        request.setCampaignType("SPOT_BUY");
        ProcurementCampaign entity = campaign("DRAFT");
        entity.setName(request.getName());
        entity.setCampaignType(request.getCampaignType());
        when(mapper.toEntity(request)).thenReturn(entity);
        stubDecisionAndProcedure("APPROVED", "REQUEST_FOR_QUOTATION", "DRAFT");
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenAnswer(invocation -> response(entity));

        service.create(request);

        assertThat(entity.getName()).isEqualTo("Bearing 6205 ZZ RFQ");
        assertThat(entity.getCampaignType()).isEqualTo("SPOT_BUY");
    }

    @Test
    void createDefaultsCompetitionProcedureToCompetitionCampaignType() {
        ProcurementCampaignCreateRequest request = request();
        ProcurementCampaign entity = campaign("DRAFT");
        entity.setCampaignType(null);
        when(mapper.toEntity(request)).thenReturn(entity);
        stubDecisionAndProcedure("APPROVED", "OPEN_COMPETITION", "DRAFT");
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenAnswer(invocation -> response(entity));

        ProcurementCampaignResponse created = service.create(request);

        assertThat(created.getCampaignType()).isEqualTo("COMPETITION");
        verify(procedureService).linkCampaign(PROCEDURE_ID, CAMPAIGN_ID);
    }

    @Test
    void createDerivesTargetSupplierCountFromApprovedDecisionAndIgnoresRequestDefault() {
        ProcurementCampaignCreateRequest request = request();
        ProcurementCampaign entity = campaign("DRAFT");
        entity.setTargetSupplierCount(3);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(procedureRepository.findByIdAndIsDeletedFalse(PROCEDURE_ID))
            .thenReturn(Optional.of(procedure("OPEN_TENDER", "DRAFT")));
        when(decisionRepository.findByIdAndIsDeletedFalse(DECISION_ID))
            .thenReturn(Optional.of(decision("APPROVED", 5)));
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenAnswer(invocation -> response(entity));

        ProcurementCampaignResponse created = service.create(request);

        assertThat(created.getTargetSupplierCount()).isEqualTo(5);
        verify(procedureService).linkCampaign(PROCEDURE_ID, CAMPAIGN_ID);
    }

    @Test
    void createDefaultsPlannedEndDateFromLinkedPurchaseRequestRequiredDate() {
        LocalDate requiredDate = LocalDate.of(2026, 8, 5);
        ProcurementCampaignCreateRequest request = request();
        ProcurementCampaign entity = campaign("DRAFT");
        entity.setPlannedEndDate(null);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(procedureRepository.findByIdAndIsDeletedFalse(PROCEDURE_ID))
            .thenReturn(Optional.of(procedure("OPEN_TENDER", "DRAFT", PURCHASE_REQUEST_ID)));
        when(decisionRepository.findByIdAndIsDeletedFalse(DECISION_ID))
            .thenReturn(Optional.of(decision("APPROVED", 2)));
        when(purchaseRequestRepository.findByIdAndIsDeletedFalse(PURCHASE_REQUEST_ID))
            .thenReturn(Optional.of(purchaseRequest(requiredDate)));
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenAnswer(invocation -> response(entity));

        ProcurementCampaignResponse created = service.create(request);

        assertThat(created.getPlannedEndDate()).isEqualTo(requiredDate);
        verify(procedureService).linkCampaign(PROCEDURE_ID, CAMPAIGN_ID);
    }

    @Test
    void createRejectsUnapprovedDecision() {
        ProcurementCampaignCreateRequest request = request();
        ProcurementCampaign entity = campaign("DRAFT");
        when(mapper.toEntity(request)).thenReturn(entity);
        when(procedureRepository.findByIdAndIsDeletedFalse(PROCEDURE_ID))
            .thenReturn(Optional.of(procedure("OPEN_TENDER", "DRAFT")));
        when(decisionRepository.findByIdAndIsDeletedFalse(DECISION_ID))
            .thenReturn(Optional.of(decision("UNDER_REVIEW")));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("APPROVED procurement method decision");
    }

    @Test
    void createRejectsProcedureWithoutMethodDecision() {
        ProcurementCampaignCreateRequest request = request();
        ProcurementCampaign entity = campaign("DRAFT");
        when(mapper.toEntity(request)).thenReturn(entity);
        ProcurementProcedure procedure = procedure("OPEN_TENDER", "DRAFT");
        procedure.setMethodDecisionId(null);
        when(procedureRepository.findByIdAndIsDeletedFalse(PROCEDURE_ID)).thenReturn(Optional.of(procedure));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("missing method decision");
    }

    @Test
    void createRfqRejectsNonRfqProcedureMethod() {
        ProcurementCampaign entity = campaign("LAUNCHED");
        stubExistingCampaign(entity);
        stubDecisionAndProcedure("APPROVED", "SINGLE_SOURCE", "LAUNCHED");

        assertThatThrownBy(() -> service.createRfq(CAMPAIGN_ID, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not use the RFQ engine");
    }

    @Test
    void createRfqUsesImmutableProcedureMethodAndLinksProcedure() {
        ProcurementCampaign entity = campaign("LAUNCHED");
        entity.setName("Bearing 6205 ZZ RFQ");
        entity.setPlannedEndDate(LocalDate.of(2026, 9, 30));
        entity.setProcedureType("SINGLE_SOURCE");
        stubExistingCampaign(entity);
        stubDecisionAndProcedure("APPROVED", "OPEN_TENDER", "LAUNCHED");
        when(rfqService.createLinked(any(RFQ.class))).thenAnswer(invocation -> {
            RFQ rfq = invocation.getArgument(0);
            rfq.setId(RFQ_ID);
            return rfq;
        });
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenAnswer(invocation -> response(entity));
        when(priceDiscoveryService.supplierIds(any(), anyBoolean())).thenReturn(Set.of());

        service.createRfq(CAMPAIGN_ID, null);

        ArgumentCaptor<RFQ> captor = ArgumentCaptor.forClass(RFQ.class);
        verify(rfqService).createLinked(captor.capture());
        assertThat(captor.getValue().getTenderProcedureType()).isEqualTo("OPEN_TENDER");
        assertThat(LocalizedText.translate(captor.getValue().getName(), Locale.ENGLISH))
            .isEqualTo("Bearing 6205 ZZ RFQ");
        assertThat(captor.getValue().getDueDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        verify(procedureService).linkRfq(PROCEDURE_ID, RFQ_ID);
    }

    @Test
    void createRfqAllocatesSelectedAggregationLinesWhenRequestCarriesGroup() {
        UUID itemId = UUID.randomUUID();
        ProcurementCampaign entity = campaign("LAUNCHED");
        stubExistingCampaign(entity);
        stubDecisionAndProcedure("APPROVED", "REQUEST_FOR_QUOTATION", "LAUNCHED");
        when(rfqService.createLinked(any(RFQ.class))).thenAnswer(invocation -> {
            RFQ rfq = invocation.getArgument(0);
            rfq.setId(RFQ_ID);
            return rfq;
        });
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenAnswer(invocation -> response(entity));
        when(priceDiscoveryService.supplierIds(any(), anyBoolean())).thenReturn(Set.of());
        CreateCampaignRfqRequest request = new CreateCampaignRfqRequest(
            LocalDate.now().plusDays(7),
            null,
            null,
            "ru",
            List.of(),
            null,
            RfqAggregationMode.MATERIAL,
            "MATERIAL|" + CompanyContext.DEFAULT_COMPANY_ID + "|UZS|" + UUID.randomUUID() + "|EA",
            List.of(itemId),
            "LOWEST_PRICE",
            1);

        service.createRfq(CAMPAIGN_ID, request);

        verify(rfqAggregationService).allocateToCampaignRfq(
            any(RFQ.class),
            org.mockito.ArgumentMatchers.eq(RfqAggregationMode.MATERIAL),
            org.mockito.ArgumentMatchers.eq(request.groupKey()),
            org.mockito.ArgumentMatchers.eq(List.of(itemId)));
        verify(rfqSourceService, times(0)).copyPurchaseRequestLines(any(RFQ.class));
    }

    @Test
    void launchSkipsProcedureAdvanceWhenProcedureAlreadyLaunched() {
        ProcurementCampaign entity = campaign("PLANNED");
        stubExistingCampaign(entity);
        stubDecisionAndProcedure("APPROVED", "OPEN_TENDER", "LAUNCHED");
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenAnswer(invocation -> response(entity));

        ProcurementCampaignResponse launched = service.launch(CAMPAIGN_ID);

        assertThat(launched.getStatus()).isEqualTo("LAUNCHED");
        verify(procedureService, times(0)).ready(PROCEDURE_ID);
        verify(procedureService, times(0)).launch(PROCEDURE_ID);
    }

    @Test
    void launchRejectsCampaignWhenProcedureCannotBeLaunched() {
        ProcurementCampaign entity = campaign("PLANNED");
        stubExistingCampaign(entity);
        stubDecisionAndProcedure("APPROVED", "OPEN_TENDER", "COMPLETED");

        assertThatThrownBy(() -> service.launch(CAMPAIGN_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current status: COMPLETED");

        verify(procedureService, times(0)).ready(PROCEDURE_ID);
        verify(procedureService, times(0)).launch(PROCEDURE_ID);
    }

    private ProcurementCampaignCreateRequest request() {
        ProcurementCampaignCreateRequest request = new ProcurementCampaignCreateRequest();
        request.setName("Campaign governed by method decision");
        request.setProcurementProcedureId(PROCEDURE_ID);
        request.setTargetSupplierCount(3);
        return request;
    }

    private ProcurementCampaign campaign(String status) {
        ProcurementCampaign entity = new ProcurementCampaign();
        entity.setId(CAMPAIGN_ID);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setCode("CAM-1");
        entity.setName("Procurement Campaign - Chemical Procurement for Production");
        entity.setCampaignNumber("CAM-1");
        entity.setStatus(status);
        entity.setProcurementPlanId(PLAN_ID);
        entity.setMethodDecisionId(DECISION_ID);
        entity.setProcurementProcedureId(PROCEDURE_ID);
        entity.setTargetSupplierCount(3);
        entity.setPlannedSpendAmount(BigDecimal.valueOf(10_000));
        entity.setApprovedSpendAmount(BigDecimal.valueOf(10_000));
        entity.setSavingsTargetAmount(BigDecimal.ZERO);
        entity.setCurrencyCode("UZS");
        return entity;
    }

    private ProcurementMethodDecision decision(String status) {
        return decision(status, 3);
    }

    private ProcurementMethodDecision decision(String status, Integer potentialSupplierCount) {
        ProcurementMethodDecision decision = new ProcurementMethodDecision();
        decision.setId(DECISION_ID);
        decision.setTenantId("default");
        decision.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        decision.setStatus(status);
        decision.setPotentialSupplierCount(potentialSupplierCount);
        return decision;
    }

    private ProcurementProcedure procedure(String method, String status) {
        return procedure(method, status, null);
    }

    private ProcurementProcedure procedure(String method, String status, UUID purchaseRequestId) {
        ProcurementProcedure procedure = new ProcurementProcedure();
        procedure.setId(PROCEDURE_ID);
        procedure.setTenantId("default");
        procedure.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        procedure.setName("Procurement Procedure - Chemical Procurement for Production");
        procedure.setStatus(status);
        procedure.setMethodDecisionId(DECISION_ID);
        procedure.setProcurementPlanId(PLAN_ID);
        procedure.setPurchaseRequestId(purchaseRequestId);
        procedure.setMethod(method);
        procedure.setCampaignId(CAMPAIGN_ID);
        return procedure;
    }

    private PurchaseRequest purchaseRequest(LocalDate requiredDate) {
        PurchaseRequest request = new PurchaseRequest();
        request.setId(PURCHASE_REQUEST_ID);
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        request.setStatus("APPROVED");
        request.setRequiredDate(requiredDate);
        return request;
    }

    private void stubDecisionAndProcedure(String decisionStatus, String method, String procedureStatus) {
        when(decisionRepository.findByIdAndIsDeletedFalse(DECISION_ID)).thenReturn(Optional.of(decision(decisionStatus)));
        when(procedureRepository.findByIdAndIsDeletedFalse(PROCEDURE_ID))
            .thenReturn(Optional.of(procedure(method, procedureStatus)));
    }

    private void stubExistingCampaign(ProcurementCampaign entity) {
        when(repository.findByIdAndIsDeletedFalse(CAMPAIGN_ID)).thenReturn(Optional.of(entity));
    }

    private ProcurementCampaignResponse response(ProcurementCampaign entity) {
        ProcurementCampaignResponse response = new ProcurementCampaignResponse();
        response.setId(entity.getId());
        response.setStatus(entity.getStatus());
        response.setProcedureType(entity.getProcedureType());
        response.setCampaignType(entity.getCampaignType());
        response.setTargetSupplierCount(entity.getTargetSupplierCount());
        response.setPlannedEndDate(entity.getPlannedEndDate());
        response.setRfqId(entity.getRfqId());
        return response;
    }
}

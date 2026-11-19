package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.NonMovingStockDecisionLine;
import com.enterprise.mm.entity.NonMovingStockExecution;
import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.NonMovingStockCaseStatus;
import com.enterprise.mm.enums.NonMovingStockDecisionType;
import com.enterprise.mm.enums.NonMovingStockExecutionOutputStatus;
import com.enterprise.mm.enums.NonMovingStockExecutionStatus;
import com.enterprise.mm.enums.NonMovingStockReconciliationStatus;
import com.enterprise.mm.enums.NonMovingStockStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.NonMovingStockCaseMapper;
import com.enterprise.mm.payload.NonMovingStockExecutionResponse;
import com.enterprise.mm.payload.NonMovingStockDecisionCreateRequest;
import com.enterprise.mm.payload.NonMovingStockDecisionLineRequest;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentResponse;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.StockValuationDocumentCreateRequest;
import com.enterprise.mm.payload.StockValuationDocumentResponse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockCaseRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class NonMovingStockCaseExecutionTest {

    @Mock NonMovingStockCaseRepository caseRepository;
    @Mock NonMovingStockCaseLineRepository lineRepository;
    @Mock NonMovingStockDecisionRepository decisionRepository;
    @Mock NonMovingStockExecutionRepository executionRepository;
    @Mock NonMovingStockRepository nonMovingStockRepository;
    @Mock StockBalanceRepository stockBalanceRepository;
    @Mock StockMovementRepository stockMovementRepository;
    @Mock MaterialRepository materialRepository;
    @Mock InventoryAccountingSettingsRepository inventoryAccountingSettingsRepository;
    @Mock StockMovementService stockMovementService;
    @Mock StockMovementDocumentService stockMovementDocumentService;
    @Mock StockValuationDocumentService stockValuationDocumentService;
    @Mock IntegrationEventService integrationEventService;
    @Mock ApprovalEngineService approvalEngineService;
    @Mock NonMovingStockCommissionService nonMovingStockCommissionService;
    @Mock NonMovingStockCaseMapper mapper;
    @Mock AuditService auditService;
    @Mock UserRepository userRepository;
    @Mock NonMovingStockCaseLifecycle lifecycle;
    @Mock NonMovingStockDecisionService decisionService;
    @Mock NonMovingStockExecutionService executionService;
    @Mock NonMovingStockReconciliationService reconciliationService;
    @Mock NonMovingStockEvidencePolicy evidencePolicy;
    @Mock NonMovingStockNotificationService notificationService;
    @Mock NonMovingStockActionPolicy nonMovingStockActionPolicy;

    @InjectMocks
    private NonMovingStockCaseService service;

    private UUID companyId;

    @BeforeEach
    void setUpScope() {
        TenantContext.setTenant("default");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @EnumSource(value = NonMovingStockDecisionType.class, names = {"WRITE_OFF", "SCRAP"})
    void homogeneousStockDecisionExecutesPersistedLineQuantities(
        NonMovingStockDecisionType decisionType
    ) {
        ExecutionFixture fixture = fixture(decisionType, "9");
        NonMovingStockCaseLine first = caseLine(fixture.stockCase(), "10", "100", UUID.randomUUID());
        NonMovingStockCaseLine second = caseLine(fixture.stockCase(), "10", "200", UUID.randomUUID());
        List<NonMovingStockDecisionLine> allocations = List.of(
            decisionLine(fixture.decision(), first, decisionType, "2", null, null),
            decisionLine(fixture.decision(), second, decisionType, "7", null, null)
        );
        arrangeExecution(fixture, List.of(first, second), allocations, false);
        doAnswer(invocation -> {
            StockMovementResponse response = new StockMovementResponse();
            response.setId(UUID.randomUUID());
            response.setCode("MOV-" + response.getId());
            return response;
        }).when(stockMovementService).createAndPost(any(StockMovement.class));

        service.executeDecision(fixture.stockCase().getId(), fixture.decision().getId(), "execute-lines", -1L);

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementService, times(2)).createAndPost(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(StockMovement::getMaterialId, StockMovement::getWarehouseId, StockMovement::getQuantity)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(first.getMaterialId(), first.getWarehouseId(), new BigDecimal("2")),
                org.assertj.core.groups.Tuple.tuple(second.getMaterialId(), second.getWarehouseId(), new BigDecimal("7"))
            );
    }

    @Test
    void homogeneousTransferCreatesOneDocumentPerSourceWarehouseWithExactAllocations() {
        ExecutionFixture fixture = fixture(NonMovingStockDecisionType.TRANSFER, "8");
        UUID targetWarehouseId = UUID.randomUUID();
        NonMovingStockCaseLine first = caseLine(fixture.stockCase(), "10", "100", UUID.randomUUID());
        NonMovingStockCaseLine second = caseLine(fixture.stockCase(), "10", "200", UUID.randomUUID());
        List<NonMovingStockDecisionLine> allocations = List.of(
            decisionLine(fixture.decision(), first, NonMovingStockDecisionType.TRANSFER, "3", targetWarehouseId, null),
            decisionLine(fixture.decision(), second, NonMovingStockDecisionType.TRANSFER, "5", targetWarehouseId, null)
        );
        arrangeExecution(fixture, List.of(first, second), allocations, true);
        doAnswer(invocation -> {
            StockMovementDocumentCreateRequest request = invocation.getArgument(0);
            StockMovementDocumentResponse response = new StockMovementDocumentResponse();
            response.setId(UUID.randomUUID());
            response.setCode("TRF-" + request.getWarehouseId());
            return response;
        }).when(stockMovementDocumentService).create(any(StockMovementDocumentCreateRequest.class));

        service.executeDecision(fixture.stockCase().getId(), fixture.decision().getId(), "execute-transfer-lines", -1L);

        ArgumentCaptor<StockMovementDocumentCreateRequest> captor =
            ArgumentCaptor.forClass(StockMovementDocumentCreateRequest.class);
        verify(stockMovementDocumentService, times(2)).create(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(StockMovementDocumentCreateRequest::getWarehouseId)
            .containsExactly(first.getWarehouseId(), second.getWarehouseId());
        assertThat(captor.getAllValues()).allSatisfy(request -> {
            assertThat(request.getTargetWarehouseId()).isEqualTo(targetWarehouseId);
            assertThat(request.getItems()).hasSize(1);
        });
        assertThat(captor.getAllValues().get(0).getItems().getFirst().getQuantity()).isEqualByComparingTo("3");
        assertThat(captor.getAllValues().get(1).getItems().getFirst().getQuantity()).isEqualByComparingTo("5");
    }

    @Test
    void homogeneousImpairmentUsesPersistedLineAmountsInsteadOfAggregateRedistribution() {
        ExecutionFixture fixture = fixture(NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "8");
        fixture.decision().setImpairmentReserveAmount(new BigDecimal("120"));
        NonMovingStockCaseLine first = caseLine(fixture.stockCase(), "4", "100", UUID.randomUUID());
        NonMovingStockCaseLine second = caseLine(fixture.stockCase(), "4", "200", UUID.randomUUID());
        List<NonMovingStockDecisionLine> allocations = List.of(
            decisionLine(fixture.decision(), first, NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "4", null, "30"),
            decisionLine(fixture.decision(), second, NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "4", null, "90")
        );
        arrangeExecution(fixture, List.of(first, second), allocations, false);
        doAnswer(invocation -> {
            StockValuationDocumentResponse response = new StockValuationDocumentResponse();
            response.setId(UUID.randomUUID());
            response.setCode("VAL-" + response.getId());
            return response;
        }).when(stockValuationDocumentService).create(any(StockValuationDocumentCreateRequest.class));

        service.executeDecision(fixture.stockCase().getId(), fixture.decision().getId(), "execute-impairment-lines", -1L);

        ArgumentCaptor<StockValuationDocumentCreateRequest> captor =
            ArgumentCaptor.forClass(StockValuationDocumentCreateRequest.class);
        verify(stockValuationDocumentService, times(2)).create(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(
                StockValuationDocumentCreateRequest::getMaterialId,
                StockValuationDocumentCreateRequest::getQuantity,
                StockValuationDocumentCreateRequest::getReserveAmount
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(first.getMaterialId(), new BigDecimal("4"), new BigDecimal("30")),
                org.assertj.core.groups.Tuple.tuple(second.getMaterialId(), new BigDecimal("4"), new BigDecimal("90"))
            );
    }

    @Test
    void impairmentValuationOutputRemainsPendingUntilAccountingPostsIt() {
        ExecutionFixture fixture = fixture(NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "4");
        fixture.decision().setImpairmentReserveAmount(new BigDecimal("30"));
        NonMovingStockCaseLine caseLine = caseLine(fixture.stockCase(), "4", "100", UUID.randomUUID());
        NonMovingStockDecisionLine allocation = decisionLine(
            fixture.decision(), caseLine, NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "4", null, "30"
        );
        arrangeExecution(fixture, List.of(caseLine), List.of(allocation), true);
        StockValuationDocumentResponse valuation = new StockValuationDocumentResponse();
        valuation.setId(UUID.randomUUID());
        valuation.setCode("VAL-PENDING");
        when(stockValuationDocumentService.create(any(StockValuationDocumentCreateRequest.class)))
            .thenReturn(valuation);

        service.executeDecision(
            fixture.stockCase().getId(), fixture.decision().getId(), "execute-impairment-lines", -1L
        );

        ArgumentCaptor<String> documentType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<NonMovingStockExecutionOutputStatus> outputStatus =
            ArgumentCaptor.forClass(NonMovingStockExecutionOutputStatus.class);
        verify(executionService, times(2)).recordOutput(
            any(), any(), any(), any(), documentType.capture(), any(), any(), outputStatus.capture(),
            any(), any(), any()
        );
        int valuationOutput = documentType.getAllValues().indexOf("STOCK_VALUATION_DOCUMENT");
        assertThat(valuationOutput).isGreaterThanOrEqualTo(0);
        assertThat(outputStatus.getAllValues().get(valuationOutput))
            .isEqualTo(NonMovingStockExecutionOutputStatus.PENDING);
        assertThat(fixture.execution().getExecutionStatus())
            .isEqualTo(NonMovingStockExecutionStatus.PENDING.name());
    }

    @Test
    void legacyImpairmentWithoutPersistedLinesAlsoWaitsForAccountingPost() {
        ExecutionFixture fixture = fixture(NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "4");
        fixture.decision().setImpairmentReserveAmount(new BigDecimal("30"));
        NonMovingStockCaseLine caseLine = caseLine(fixture.stockCase(), "4", "100", UUID.randomUUID());
        arrangeExecution(fixture, List.of(caseLine), List.of(), true);
        StockValuationDocumentResponse valuation = new StockValuationDocumentResponse();
        valuation.setId(UUID.randomUUID());
        valuation.setCode("VAL-LEGACY-PENDING");
        when(stockValuationDocumentService.create(any(StockValuationDocumentCreateRequest.class)))
            .thenReturn(valuation);

        service.executeDecision(
            fixture.stockCase().getId(), fixture.decision().getId(), "execute-impairment-lines", -1L
        );

        ArgumentCaptor<String> documentType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<NonMovingStockExecutionOutputStatus> outputStatus =
            ArgumentCaptor.forClass(NonMovingStockExecutionOutputStatus.class);
        verify(executionService, times(2)).recordOutput(
            any(), any(), any(), any(), documentType.capture(), any(), any(), outputStatus.capture(),
            any(), any(), any()
        );
        int valuationOutput = documentType.getAllValues().indexOf("STOCK_VALUATION_DOCUMENT");
        assertThat(valuationOutput).isGreaterThanOrEqualTo(0);
        assertThat(outputStatus.getAllValues().get(valuationOutput))
            .isEqualTo(NonMovingStockExecutionOutputStatus.PENDING);
        assertThat(fixture.execution().getExecutionStatus())
            .isEqualTo(NonMovingStockExecutionStatus.PENDING.name());
    }

    @Test
    void legacyImpairmentTracksEveryCreatedValuationDocumentUntilPosting() {
        ExecutionFixture fixture = fixture(NonMovingStockDecisionType.IMPAIRMENT_RESERVE, "8");
        fixture.decision().setImpairmentReserveAmount(new BigDecimal("120"));
        NonMovingStockCaseLine first = caseLine(fixture.stockCase(), "4", "100", UUID.randomUUID());
        NonMovingStockCaseLine second = caseLine(fixture.stockCase(), "4", "200", UUID.randomUUID());
        arrangeExecution(fixture, List.of(first, second), List.of(), true);
        StockValuationDocumentResponse firstValuation = new StockValuationDocumentResponse();
        firstValuation.setId(UUID.randomUUID());
        firstValuation.setCode("VAL-LEGACY-1");
        StockValuationDocumentResponse secondValuation = new StockValuationDocumentResponse();
        secondValuation.setId(UUID.randomUUID());
        secondValuation.setCode("VAL-LEGACY-2");
        when(stockValuationDocumentService.create(any(StockValuationDocumentCreateRequest.class)))
            .thenReturn(firstValuation, secondValuation);

        service.executeDecision(
            fixture.stockCase().getId(), fixture.decision().getId(), "execute-impairment-lines", -1L
        );

        ArgumentCaptor<String> documentType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> documentId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<NonMovingStockExecutionOutputStatus> outputStatus =
            ArgumentCaptor.forClass(NonMovingStockExecutionOutputStatus.class);
        verify(executionService, times(3)).recordOutput(
            any(), any(), any(), any(), documentType.capture(), documentId.capture(), any(), outputStatus.capture(),
            any(), any(), any()
        );
        assertThat(documentType.getAllValues()).containsExactly(
            "STOCK_VALUATION_DOCUMENT", "STOCK_VALUATION_DOCUMENT", "INTEGRATION_EVENT"
        );
        assertThat(documentId.getAllValues()).containsExactly(
            firstValuation.getId(), secondValuation.getId(), fixture.execution().getIntegrationEventId()
        );
        assertThat(outputStatus.getAllValues()).containsExactly(
            NonMovingStockExecutionOutputStatus.PENDING,
            NonMovingStockExecutionOutputStatus.PENDING,
            NonMovingStockExecutionOutputStatus.POSTED
        );
    }

    @Test
    void mixedDecisionKeepsTheSameLineAllocationContract() {
        ExecutionFixture fixture = fixture(NonMovingStockDecisionType.MIXED, "6");
        UUID targetWarehouseId = UUID.randomUUID();
        NonMovingStockCaseLine writeOffLine = caseLine(fixture.stockCase(), "10", "100", UUID.randomUUID());
        NonMovingStockCaseLine transferLine = caseLine(fixture.stockCase(), "10", "200", UUID.randomUUID());
        List<NonMovingStockDecisionLine> allocations = List.of(
            decisionLine(fixture.decision(), writeOffLine, NonMovingStockDecisionType.WRITE_OFF, "2", null, null),
            decisionLine(fixture.decision(), transferLine, NonMovingStockDecisionType.TRANSFER, "4", targetWarehouseId, null)
        );
        arrangeExecution(fixture, List.of(writeOffLine, transferLine), allocations, true);
        doAnswer(invocation -> {
            StockMovementResponse response = new StockMovementResponse();
            response.setId(UUID.randomUUID());
            response.setCode("MOV-" + response.getId());
            return response;
        }).when(stockMovementService).createAndPost(any(StockMovement.class));
        doAnswer(invocation -> {
            StockMovementDocumentResponse response = new StockMovementDocumentResponse();
            response.setId(UUID.randomUUID());
            response.setCode("TRF-" + response.getId());
            return response;
        }).when(stockMovementDocumentService).create(any(StockMovementDocumentCreateRequest.class));

        service.executeDecision(fixture.stockCase().getId(), fixture.decision().getId(), "execute-mixed-lines", -1L);

        ArgumentCaptor<StockMovement> movementCaptor = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementService).createAndPost(movementCaptor.capture());
        assertThat(movementCaptor.getValue().getMaterialId()).isEqualTo(writeOffLine.getMaterialId());
        assertThat(movementCaptor.getValue().getQuantity()).isEqualByComparingTo("2");
        ArgumentCaptor<StockMovementDocumentCreateRequest> transferCaptor =
            ArgumentCaptor.forClass(StockMovementDocumentCreateRequest.class);
        verify(stockMovementDocumentService).create(transferCaptor.capture());
        assertThat(transferCaptor.getValue().getWarehouseId()).isEqualTo(transferLine.getWarehouseId());
        assertThat(transferCaptor.getValue().getTargetWarehouseId()).isEqualTo(targetWarehouseId);
        assertThat(transferCaptor.getValue().getItems().getFirst().getQuantity()).isEqualByComparingTo("4");
    }

    @Test
    void retryWithSameIdempotencyKeyDoesNotCreateAnyDownstreamOutput() {
        ExecutionFixture fixture = fixture(NonMovingStockDecisionType.WRITE_OFF, "4");
        NonMovingStockExecution existing = new NonMovingStockExecution();
        existing.setId(UUID.randomUUID());
        existing.setCaseId(fixture.stockCase().getId());
        existing.setDecisionId(fixture.decision().getId());
        when(caseRepository.findByIdAndIsDeletedFalse(fixture.stockCase().getId()))
            .thenReturn(Optional.of(fixture.stockCase()));
        when(executionService.idempotent(companyId, "same-key")).thenReturn(Optional.of(existing));
        when(executionService.outputs(existing.getId())).thenReturn(List.of());
        when(mapper.toExecutionResponse(existing, List.of())).thenReturn(new NonMovingStockExecutionResponse());

        service.executeDecision(fixture.stockCase().getId(), fixture.decision().getId(), "same-key", -1L);

        verify(executionService, never()).begin(any(), any(), any());
        verifyNoInteractions(stockMovementService, stockMovementDocumentService, stockValuationDocumentService);
    }

    @Test
    void sameKeyReplayReturnsOriginalExecutionBeforeCheckingStaleCaseVersion() {
        ExecutionFixture fixture = fixture(NonMovingStockDecisionType.TRANSFER, "4");
        fixture.stockCase().setVersion(7L);
        NonMovingStockExecution existing = new NonMovingStockExecution();
        existing.setId(UUID.randomUUID());
        existing.setCaseId(fixture.stockCase().getId());
        existing.setDecisionId(fixture.decision().getId());
        NonMovingStockExecutionResponse expected = new NonMovingStockExecutionResponse();
        when(caseRepository.findByIdAndIsDeletedFalse(fixture.stockCase().getId()))
            .thenReturn(Optional.of(fixture.stockCase()));
        when(executionService.idempotent(companyId, "same-transfer-key")).thenReturn(Optional.of(existing));
        when(executionService.outputs(existing.getId())).thenReturn(List.of());
        when(mapper.toExecutionResponse(existing, List.of())).thenReturn(expected);

        NonMovingStockExecutionResponse actual = service.executeDecision(
            fixture.stockCase().getId(), fixture.decision().getId(), "same-transfer-key", 1L
        );

        assertThat(actual).isSameAs(expected);
        verify(decisionRepository, never()).findByIdAndIsDeletedFalse(any());
        verifyNoInteractions(stockMovementService, stockMovementDocumentService, stockValuationDocumentService);
    }

    @Test
    void differentKeyCannotCreateSecondPendingExecutionForTheSameDecision() {
        ExecutionFixture fixture = fixture(NonMovingStockDecisionType.TRANSFER, "4");
        UUID targetWarehouseId = UUID.randomUUID();
        NonMovingStockCaseLine caseLine = caseLine(
            fixture.stockCase(), "4", "100", UUID.randomUUID()
        );
        NonMovingStockDecisionLine allocation = decisionLine(
            fixture.decision(), caseLine, NonMovingStockDecisionType.TRANSFER,
            "4", targetWarehouseId, null
        );
        NonMovingStockExecution active = new NonMovingStockExecution();
        active.setId(UUID.randomUUID());
        active.setTenantId("default");
        active.setCompanyId(companyId);
        active.setCaseId(fixture.stockCase().getId());
        active.setDecisionId(fixture.decision().getId());
        active.setExecutionStatus(NonMovingStockExecutionStatus.PENDING.name());
        active.setStatus(NonMovingStockExecutionStatus.PENDING.name());
        active.setIdempotencyKey("first-key");

        when(caseRepository.findByIdAndIsDeletedFalse(fixture.stockCase().getId()))
            .thenReturn(Optional.of(fixture.stockCase()));
        when(caseRepository.findByIdAndIsDeletedFalseForUpdate(fixture.stockCase().getId()))
            .thenReturn(Optional.of(fixture.stockCase()));
        when(executionService.idempotent(companyId, "different-key")).thenReturn(Optional.empty());
        when(executionRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", fixture.stockCase().getId()
        )).thenReturn(List.of(active));
        when(decisionRepository.findByIdAndIsDeletedFalse(fixture.decision().getId()))
            .thenReturn(Optional.of(fixture.decision()));

        assertThatThrownBy(() -> service.executeDecision(
            fixture.stockCase().getId(), fixture.decision().getId(), "different-key", -1L
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active execution");

        verify(executionService, never()).begin(any(), any(), any());
        verifyNoInteractions(stockMovementService, stockMovementDocumentService, stockValuationDocumentService);
    }

    @ParameterizedTest
    @EnumSource(
        value = NonMovingStockDecisionType.class,
        names = {"TRANSFER", "IMPAIRMENT_RESERVE", "WRITE_OFF", "SCRAP"}
    )
    void lineDecisionContractIsNotRejectedByLegacyAggregateValidation(
        NonMovingStockDecisionType lineType
    ) {
        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setId(UUID.randomUUID());
        stockCase.setTenantId("default");
        stockCase.setCompanyId(companyId);
        stockCase.setCaseNo("NMS-LINE-VALIDATION");
        stockCase.setStatus(NonMovingStockCaseStatus.COMMISSION_REVIEW.name());
        stockCase.setTotalQuantity(new BigDecimal("10"));
        stockCase.setVersion(1L);
        UUID commissionId = UUID.randomUUID();
        stockCase.setCommissionId(commissionId);
        NonMovingStockCaseLine caseLine = caseLine(
            stockCase, "10", "100", UUID.randomUUID()
        );

        NonMovingStockDecisionLineRequest line = new NonMovingStockDecisionLineRequest();
        line.setCaseLineId(caseLine.getId());
        line.setDecisionType(lineType.name());
        line.setApprovedQuantity(new BigDecimal("4"));
        line.setTargetWarehouseId(UUID.randomUUID());
        line.setFinancialAmount(new BigDecimal("25"));
        line.setCurrencyCode("USD");
        line.setControlledReason("Approved line-level reason");
        NonMovingStockDecisionCreateRequest request = new NonMovingStockDecisionCreateRequest();
        request.setLines(List.of(line));
        if (lineType == NonMovingStockDecisionType.TRANSFER) {
            request.setTargetWarehouseId(caseLine.getWarehouseId());
        }

        Commission commission = new Commission();
        commission.setId(commissionId);
        commission.setCompanyId(companyId);
        when(caseRepository.findByIdAndIsDeletedFalse(stockCase.getId()))
            .thenReturn(Optional.of(stockCase));
        when(nonMovingStockCommissionService.requireNonMovingStockCommission(commissionId))
            .thenReturn(commission);
        when(lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", stockCase.getId()
        )).thenReturn(List.of(caseLine));
        when(decisionRepository.save(any(NonMovingStockDecision.class))).thenAnswer(invocation -> {
            NonMovingStockDecision saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(decisionService.markSubmitted(any(NonMovingStockDecision.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(lifecycle.status(stockCase.getStatus()))
            .thenReturn(NonMovingStockCaseStatus.COMMISSION_REVIEW);

        assertThatCode(() -> service.approveDecision(stockCase.getId(), request))
            .doesNotThrowAnyException();

        verify(decisionService).persistLines(any(), anyList(), any());
    }

    @Test
    void caseCreationRejectsTheWholeSelectionWhenAnyDetectionIsIneligible() {
        NonMovingStock eligible = detection(NonMovingStockStatus.DETECTED);
        NonMovingStock ineligible = detection(NonMovingStockStatus.CASE_CREATED);
        when(nonMovingStockRepository.findByIdAndIsDeletedFalseForUpdate(eligible.getId()))
            .thenReturn(Optional.of(eligible));
        when(nonMovingStockRepository.findByIdAndIsDeletedFalseForUpdate(ineligible.getId()))
            .thenReturn(Optional.of(ineligible));

        assertThatThrownBy(() -> service.createCaseFromDetected(
            List.of(eligible.getId(), ineligible.getId())
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not eligible")
            .hasMessageContaining(ineligible.getId().toString());

        verify(caseRepository, never()).save(any(NonMovingStockCase.class));
        verify(lineRepository, never()).save(any(NonMovingStockCaseLine.class));
    }

    @Test
    void caseCreationRejectsDuplicateDetectionIdsBeforeLoadingOrWriting() {
        UUID duplicateId = UUID.randomUUID();

        assertThatThrownBy(() -> service.createCaseFromDetected(
            List.of(duplicateId, duplicateId)
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("distinct")
            .hasMessageContaining(duplicateId.toString());

        verify(nonMovingStockRepository, never()).findByIdAndIsDeletedFalseForUpdate(any());
        verify(caseRepository, never()).save(any(NonMovingStockCase.class));
    }

    @Test
    void caseCreationRejectsDetectionAlreadyAssignedToActiveCase() {
        NonMovingStock detected = detection(NonMovingStockStatus.DETECTED);
        UUID activeCaseId = UUID.randomUUID();
        NonMovingStockCaseLine activeMembership = new NonMovingStockCaseLine();
        activeMembership.setCaseId(activeCaseId);
        activeMembership.setNonMovingStockId(detected.getId());
        activeMembership.setActiveMembership(true);
        when(nonMovingStockRepository.findByIdAndIsDeletedFalseForUpdate(detected.getId()))
            .thenReturn(Optional.of(detected));
        when(lineRepository.findByTenantIdAndNonMovingStockIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", detected.getId()
        )).thenReturn(List.of(activeMembership));

        assertThatThrownBy(() -> service.createCaseFromDetected(List.of(detected.getId())))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active case")
            .hasMessageContaining(detected.getId().toString())
            .hasMessageContaining(activeCaseId.toString());

        verify(caseRepository, never()).save(any(NonMovingStockCase.class));
        verify(lineRepository, never()).save(any(NonMovingStockCaseLine.class));
    }

    @Test
    void caseCreationKeepsOneActiveMembershipWhenDetectionHasMultipleBalances() {
        NonMovingStock detected = detection(NonMovingStockStatus.DETECTED);
        detected.setMaterialId(UUID.randomUUID());
        detected.setWarehouseId(UUID.randomUUID());

        com.enterprise.mm.entity.StockBalance firstBalance = new com.enterprise.mm.entity.StockBalance();
        firstBalance.setCompanyId(companyId);
        firstBalance.setMaterialId(detected.getMaterialId());
        firstBalance.setWarehouseId(detected.getWarehouseId());
        firstBalance.setStorageLocationId(UUID.randomUUID());
        firstBalance.setStockBatchId(UUID.randomUUID());
        firstBalance.setQuantity(new BigDecimal("100"));
        firstBalance.setAvailableQuantity(new BigDecimal("100"));
        firstBalance.setTotalValue(new BigDecimal("1000"));

        com.enterprise.mm.entity.StockBalance secondBalance = new com.enterprise.mm.entity.StockBalance();
        secondBalance.setCompanyId(companyId);
        secondBalance.setMaterialId(detected.getMaterialId());
        secondBalance.setWarehouseId(detected.getWarehouseId());
        secondBalance.setStorageLocationId(UUID.randomUUID());
        secondBalance.setStockBatchId(UUID.randomUUID());
        secondBalance.setQuantity(new BigDecimal("55"));
        secondBalance.setAvailableQuantity(new BigDecimal("55"));
        secondBalance.setTotalValue(new BigDecimal("550"));

        java.util.List<NonMovingStockCaseLine> persistedLines = new java.util.ArrayList<>();
        java.util.Set<UUID> activeDetectionMemberships = new java.util.HashSet<>();

        when(nonMovingStockRepository.findByIdAndIsDeletedFalseForUpdate(detected.getId()))
            .thenReturn(Optional.of(detected));
        when(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            "default", detected.getMaterialId(), detected.getWarehouseId()
        )).thenReturn(List.of(firstBalance, secondBalance));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", detected.getMaterialId()))
            .thenReturn(Optional.empty());
        when(inventoryAccountingSettingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            "default", companyId
        )).thenReturn(Optional.empty());
        when(caseRepository.save(any(NonMovingStockCase.class))).thenAnswer(invocation -> {
            NonMovingStockCase saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return saved;
        });
        when(lineRepository.save(any(NonMovingStockCaseLine.class))).thenAnswer(invocation -> {
            NonMovingStockCaseLine saved = invocation.getArgument(0);
            if (saved.isActiveMembership() && !activeDetectionMemberships.add(saved.getNonMovingStockId())) {
                throw new DataIntegrityViolationException("uk_nms_active_detection_case");
            }
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            persistedLines.add(saved);
            return saved;
        });
        when(lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(any(), any()))
            .thenAnswer(invocation -> List.copyOf(persistedLines));
        when(mapper.toResponse(any(), anyList(), anyList(), anyList()))
            .thenReturn(new com.enterprise.mm.payload.NonMovingStockCaseResponse());

        assertThatCode(() -> service.createCaseFromDetected(List.of(detected.getId())))
            .doesNotThrowAnyException();

        assertThat(persistedLines).hasSize(2);
        assertThat(persistedLines).filteredOn(NonMovingStockCaseLine::isActiveMembership).hasSize(1);
        assertThat(persistedLines).extracting(NonMovingStockCaseLine::getQuantity)
            .containsExactlyInAnyOrder(new BigDecimal("100"), new BigDecimal("55"));
    }

    @Test
    void caseCreationPersistsConfiguredUzsAsReadyOnCaseAndEveryValuedLine() {
        NonMovingStock detected = detection(NonMovingStockStatus.DETECTED);
        detected.setMaterialId(UUID.randomUUID());
        detected.setWarehouseId(UUID.randomUUID());
        detected.setDetectedAt(java.time.LocalDateTime.now().minusMonths(13));
        com.enterprise.mm.entity.StockBalance balance = new com.enterprise.mm.entity.StockBalance();
        balance.setCompanyId(companyId);
        balance.setMaterialId(detected.getMaterialId());
        balance.setWarehouseId(detected.getWarehouseId());
        balance.setQuantity(new BigDecimal("4"));
        balance.setAvailableQuantity(new BigDecimal("3"));
        balance.setReservedQuantity(BigDecimal.ONE);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(new BigDecimal("125.50"));
        balance.setBalanceAt(java.time.LocalDateTime.now());
        InventoryAccountingSettings settings = new InventoryAccountingSettings();
        settings.setBaseCurrencyCode(" uzs ");
        java.util.List<NonMovingStockCaseLine> persistedLines = new java.util.ArrayList<>();

        when(nonMovingStockRepository.findByIdAndIsDeletedFalseForUpdate(detected.getId()))
            .thenReturn(Optional.of(detected));
        when(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            "default", detected.getMaterialId(), detected.getWarehouseId()
        )).thenReturn(List.of(balance));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", detected.getMaterialId()))
            .thenReturn(Optional.empty());
        when(inventoryAccountingSettingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            "default", companyId
        )).thenReturn(Optional.of(settings));
        when(caseRepository.save(any(NonMovingStockCase.class))).thenAnswer(invocation -> {
            NonMovingStockCase saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return saved;
        });
        when(lineRepository.save(any(NonMovingStockCaseLine.class))).thenAnswer(invocation -> {
            NonMovingStockCaseLine saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            persistedLines.add(saved);
            return saved;
        });
        when(lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            any(), any()
        )).thenAnswer(invocation -> List.copyOf(persistedLines));
        when(mapper.toResponse(any(), anyList(), anyList(), anyList()))
            .thenReturn(new com.enterprise.mm.payload.NonMovingStockCaseResponse());

        service.createCaseFromDetected(List.of(detected.getId()));

        ArgumentCaptor<NonMovingStockCase> caseCaptor = ArgumentCaptor.forClass(NonMovingStockCase.class);
        verify(caseRepository, times(2)).save(caseCaptor.capture());
        assertThat(caseCaptor.getAllValues())
            .allSatisfy(saved -> {
                assertThat(saved.getAccountingCurrencyCode()).isEqualTo("UZS");
                assertThat(saved.getValuationHealth()).isEqualTo("READY");
                assertThat(saved.getValuationHealthMessage()).isNull();
            });
        assertThat(persistedLines).singleElement().satisfies(line -> {
            assertThat(line.getBookValue()).isEqualByComparingTo("125.50");
            assertThat(line.getUnitCost()).isEqualByComparingTo("31.3750");
            assertThat(line.getCurrencyCode()).isEqualTo("UZS");
            assertThat(line.getValuationHealth()).isEqualTo("READY");
            assertThat(line.getValuationHealthMessage()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"<missing>", "US"})
    void caseCreationPersistsAuditablePartialUzsFallbackWhenSettingsAreMissingOrInvalid(
        String configuredCurrency
    ) {
        NonMovingStock detected = detection(NonMovingStockStatus.DETECTED);
        detected.setMaterialId(UUID.randomUUID());
        detected.setWarehouseId(UUID.randomUUID());
        com.enterprise.mm.entity.StockBalance balance = new com.enterprise.mm.entity.StockBalance();
        balance.setCompanyId(companyId);
        balance.setMaterialId(detected.getMaterialId());
        balance.setWarehouseId(detected.getWarehouseId());
        balance.setQuantity(BigDecimal.TEN);
        balance.setAvailableQuantity(BigDecimal.TEN);
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(new BigDecimal("500"));
        java.util.List<NonMovingStockCaseLine> persistedLines = new java.util.ArrayList<>();

        when(nonMovingStockRepository.findByIdAndIsDeletedFalseForUpdate(detected.getId()))
            .thenReturn(Optional.of(detected));
        when(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            "default", detected.getMaterialId(), detected.getWarehouseId()
        )).thenReturn(List.of(balance));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", detected.getMaterialId()))
            .thenReturn(Optional.empty());
        Optional<InventoryAccountingSettings> settings = "<missing>".equals(configuredCurrency)
            ? Optional.empty()
            : Optional.of(inventoryAccountingSettings(configuredCurrency));
        when(inventoryAccountingSettingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            "default", companyId
        )).thenReturn(settings);
        when(caseRepository.save(any(NonMovingStockCase.class))).thenAnswer(invocation -> {
            NonMovingStockCase saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            return saved;
        });
        when(lineRepository.save(any(NonMovingStockCaseLine.class))).thenAnswer(invocation -> {
            NonMovingStockCaseLine saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            persistedLines.add(saved);
            return saved;
        });
        when(lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(any(), any()))
            .thenAnswer(invocation -> List.copyOf(persistedLines));
        when(mapper.toResponse(any(), anyList(), anyList(), anyList()))
            .thenReturn(new com.enterprise.mm.payload.NonMovingStockCaseResponse());

        service.createCaseFromDetected(List.of(detected.getId()));

        ArgumentCaptor<NonMovingStockCase> caseCaptor = ArgumentCaptor.forClass(NonMovingStockCase.class);
        verify(caseRepository, times(2)).save(caseCaptor.capture());
        assertThat(caseCaptor.getAllValues()).allSatisfy(saved -> {
            assertThat(saved.getAccountingCurrencyCode()).isEqualTo("UZS");
            assertThat(saved.getValuationHealth()).isEqualTo("PARTIAL");
            assertThat(saved.getValuationHealthMessage()).contains("settings", "UZS fallback");
        });
        assertThat(persistedLines).singleElement().satisfies(line -> {
            assertThat(line.getCurrencyCode()).isEqualTo("UZS");
            assertThat(line.getValuationHealth()).isEqualTo("PARTIAL");
            assertThat(line.getValuationHealthMessage()).contains("settings", "UZS fallback");
        });
    }

    private InventoryAccountingSettings inventoryAccountingSettings(String currencyCode) {
        InventoryAccountingSettings settings = new InventoryAccountingSettings();
        settings.setBaseCurrencyCode(currencyCode);
        return settings;
    }

    private ExecutionFixture fixture(NonMovingStockDecisionType decisionType, String approvedQuantity) {
        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setId(UUID.randomUUID());
        stockCase.setTenantId("default");
        stockCase.setCompanyId(companyId);
        stockCase.setCaseNo("NMS-CASE-TEST");
        stockCase.setStatus(NonMovingStockCaseStatus.DECISION_APPROVED.name());
        stockCase.setVersion(1L);

        NonMovingStockDecision decision = new NonMovingStockDecision();
        decision.setId(UUID.randomUUID());
        decision.setTenantId("default");
        decision.setCompanyId(companyId);
        decision.setCaseId(stockCase.getId());
        decision.setDecisionType(decisionType.name());
        decision.setApprovedQuantity(new BigDecimal(approvedQuantity));
        decision.setStatus(NonMovingStockCaseStatus.DECISION_APPROVED.name());

        NonMovingStockExecution execution = new NonMovingStockExecution();
        execution.setId(UUID.randomUUID());
        execution.setTenantId("default");
        execution.setCompanyId(companyId);
        execution.setCaseId(stockCase.getId());
        execution.setDecisionId(decision.getId());
        return new ExecutionFixture(stockCase, decision, execution);
    }

    private NonMovingStockCaseLine caseLine(
        NonMovingStockCase stockCase,
        String quantity,
        String bookValue,
        UUID sourceWarehouseId
    ) {
        NonMovingStockCaseLine line = new NonMovingStockCaseLine();
        line.setId(UUID.randomUUID());
        line.setTenantId("default");
        line.setCompanyId(companyId);
        line.setCaseId(stockCase.getId());
        line.setNonMovingStockId(UUID.randomUUID());
        line.setMaterialId(UUID.randomUUID());
        line.setWarehouseId(sourceWarehouseId);
        line.setStorageLocationId(UUID.randomUUID());
        line.setBatchId(UUID.randomUUID());
        line.setQuantity(new BigDecimal(quantity));
        line.setAvailableQuantity(new BigDecimal(quantity));
        line.setBookValue(new BigDecimal(bookValue));
        return line;
    }

    private NonMovingStock detection(NonMovingStockStatus status) {
        NonMovingStock detection = new NonMovingStock();
        detection.setId(UUID.randomUUID());
        detection.setTenantId("default");
        detection.setCompanyId(companyId);
        detection.setStatus(status.name());
        return detection;
    }

    private NonMovingStockDecisionLine decisionLine(
        NonMovingStockDecision decision,
        NonMovingStockCaseLine caseLine,
        NonMovingStockDecisionType type,
        String quantity,
        UUID targetWarehouseId,
        String financialAmount
    ) {
        NonMovingStockDecisionLine line = new NonMovingStockDecisionLine();
        line.setId(UUID.randomUUID());
        line.setTenantId("default");
        line.setCompanyId(companyId);
        line.setDecisionId(decision.getId());
        line.setCaseLineId(caseLine.getId());
        line.setDecisionType(type.name());
        line.setApprovedQuantity(new BigDecimal(quantity));
        line.setTargetWarehouseId(targetWarehouseId);
        line.setFinancialAmount(financialAmount == null ? null : new BigDecimal(financialAmount));
        line.setCurrencyCode("USD");
        line.setControlledReason("Approved by commission");
        line.setComment("Line decision");
        return line;
    }

    private void arrangeExecution(
        ExecutionFixture fixture,
        List<NonMovingStockCaseLine> caseLines,
        List<NonMovingStockDecisionLine> decisionLines,
        boolean pending
    ) {
        when(caseRepository.findByIdAndIsDeletedFalse(fixture.stockCase().getId()))
            .thenReturn(Optional.of(fixture.stockCase()));
        when(caseRepository.findByIdAndIsDeletedFalseForUpdate(fixture.stockCase().getId()))
            .thenReturn(Optional.of(fixture.stockCase()));
        when(executionService.idempotent(companyId, idempotencyKeyFor(fixture.decision())))
            .thenReturn(Optional.empty());
        when(decisionRepository.findByIdAndIsDeletedFalse(fixture.decision().getId()))
            .thenReturn(Optional.of(fixture.decision()));
        when(lineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", fixture.stockCase().getId()
        )).thenReturn(caseLines);
        when(decisionService.lines(fixture.decision().getId())).thenReturn(decisionLines);
        decisionLines.forEach(line -> when(reconciliationService.reconcile(
            caseLines.stream().filter(caseLine -> caseLine.getId().equals(line.getCaseLineId())).findFirst().orElseThrow(),
            line.getApprovedQuantity(),
            line.getId()
        )).thenReturn(new NonMovingStockReconciliationService.ReconciliationResult(
            NonMovingStockReconciliationStatus.READY,
            line.getApprovedQuantity(),
            "Ready"
        )));
        when(executionService.begin(fixture.stockCase(), fixture.decision(), idempotencyKeyFor(fixture.decision())))
            .thenReturn(fixture.execution());
        when(executionService.synchronizeStatus(fixture.execution())).thenAnswer(invocation -> {
            fixture.execution().setStatus((pending
                ? NonMovingStockExecutionStatus.PENDING
                : NonMovingStockExecutionStatus.EXECUTED).name());
            fixture.execution().setExecutionStatus(fixture.execution().getStatus());
            return fixture.execution();
        });
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        when(integrationEventService.emit(any(), any(), any(), anyMap())).thenReturn(event);
        when(executionService.outputs(fixture.execution().getId())).thenReturn(List.of());
        when(mapper.toExecutionResponse(fixture.execution(), List.of()))
            .thenReturn(new NonMovingStockExecutionResponse());
    }

    private String idempotencyKeyFor(NonMovingStockDecision decision) {
        return switch (NonMovingStockDecisionType.valueOf(decision.getDecisionType())) {
            case TRANSFER -> "execute-transfer-lines";
            case IMPAIRMENT_RESERVE -> "execute-impairment-lines";
            case MIXED -> "execute-mixed-lines";
            default -> "execute-lines";
        };
    }

    private record ExecutionFixture(
        NonMovingStockCase stockCase,
        NonMovingStockDecision decision,
        NonMovingStockExecution execution
    ) {
    }
}

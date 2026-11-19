package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.NonMovingStock;
import com.enterprise.mm.entity.NonMovingStockCase;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.NonMovingStockDecisionLine;
import com.enterprise.mm.entity.NonMovingStockExecution;
import com.enterprise.mm.entity.NonMovingStockExecutionOutput;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.enums.NonMovingStockCaseStatus;
import com.enterprise.mm.enums.NonMovingStockDecisionType;
import com.enterprise.mm.enums.NonMovingStockExecutionOutputStatus;
import com.enterprise.mm.enums.NonMovingStockExecutionStatus;
import com.enterprise.mm.enums.NonMovingStockReconciliationStatus;
import com.enterprise.mm.enums.NonMovingStockStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionOutputRepository;
import com.enterprise.mm.repository.NonMovingStockExecutionRepository;
import com.enterprise.mm.repository.NonMovingStockCaseRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NonMovingStockExecutionServiceTest {

    @Mock
    private StockBalanceRepository balanceRepository;
    @Mock
    private InventoryPeriodGuardService periodGuardService;
    @Mock
    private NonMovingStockExecutionOutputRepository outputRepository;
    @Mock
    private NonMovingStockExecutionRepository executionRepository;
    @Mock
    private NonMovingStockCaseRepository caseRepository;
    @Mock
    private NonMovingStockDecisionLineRepository decisionLineRepository;
    @Mock
    private NonMovingStockDecisionRepository decisionRepository;
    @Mock
    private NonMovingStockCaseLineRepository caseLineRepository;
    @Mock
    private NonMovingStockRepository nonMovingStockRepository;

    private NonMovingStockReconciliationService reconciliationService;
    private NonMovingStockExecutionService executionService;

    @BeforeEach
    void setUp() {
        reconciliationService = new NonMovingStockReconciliationService(
            balanceRepository,
            periodGuardService,
            outputRepository
        );
        executionService = new NonMovingStockExecutionService(
            executionRepository,
            outputRepository,
            caseRepository,
            decisionLineRepository,
            decisionRepository,
            caseLineRepository,
            nonMovingStockRepository
        );
    }

    @Test
    void matchingLiveBalanceIsReady() {
        NonMovingStockCaseLine line = caseLine("10");
        StockBalance balance = balance(line, "10", "10", "0", "0");
        when(balanceRepository.findCompanyBalance(
            line.getTenantId(), line.getCompanyId(), line.getMaterialId(), line.getWarehouseId(),
            false, line.getStorageLocationId(), true, null, false, line.getBatchId()
        )).thenReturn(Optional.of(balance));

        var result = reconciliationService.reconcile(line, BigDecimal.valueOf(5), null);

        assertThat(result.status()).isEqualTo(NonMovingStockReconciliationStatus.READY);
        assertThat(result.liveAvailableQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void insufficientLiveBalanceBlocksExecution() {
        NonMovingStockCaseLine line = caseLine("10");
        StockBalance balance = balance(line, "3", "3", "0", "0");
        when(balanceRepository.findCompanyBalance(
            line.getTenantId(), line.getCompanyId(), line.getMaterialId(), line.getWarehouseId(),
            false, line.getStorageLocationId(), true, null, false, line.getBatchId()
        )).thenReturn(Optional.of(balance));

        var result = reconciliationService.reconcile(line, BigDecimal.valueOf(5), null);

        assertThat(result.status()).isEqualTo(NonMovingStockReconciliationStatus.INSUFFICIENT_STOCK);
    }

    @Test
    void repeatedIdempotencyKeyReturnsOriginalExecutionWithoutSaving() {
        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setId(UUID.randomUUID());
        stockCase.setTenantId("tenant-a");
        stockCase.setCompanyId(UUID.randomUUID());
        NonMovingStockDecision decision = new NonMovingStockDecision();
        decision.setId(UUID.randomUUID());
        NonMovingStockExecution existing = new NonMovingStockExecution();
        existing.setId(UUID.randomUUID());
        when(executionRepository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            "tenant-a", stockCase.getCompanyId(), "execute-001"
        )).thenReturn(Optional.of(existing));

        NonMovingStockExecution result = executionService.begin(stockCase, decision, "execute-001");

        assertThat(result).isSameAs(existing);
        verify(executionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void movedStockIsClassifiedByLocationOrBatch() {
        NonMovingStockCaseLine line = caseLine("10");
        StockBalance otherBatch = balance(line, "10", "10", "0", "0");
        otherBatch.setStockBatchId(UUID.randomUUID());
        when(balanceRepository.findCompanyBalance(
            line.getTenantId(), line.getCompanyId(), line.getMaterialId(), line.getWarehouseId(),
            false, line.getStorageLocationId(), true, null, false, line.getBatchId()
        )).thenReturn(Optional.empty());
        when(balanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(
            line.getTenantId(), line.getMaterialId(), line.getWarehouseId()
        )).thenReturn(List.of(otherBatch));

        var result = reconciliationService.reconcile(line, BigDecimal.ONE, null);

        assertThat(result.status()).isEqualTo(NonMovingStockReconciliationStatus.BATCH_CHANGED);
    }

    @Test
    void completedOutputsAdvanceExecutionOnlyWhenAllArePosted() {
        NonMovingStockExecution execution = new NonMovingStockExecution();
        execution.setId(UUID.randomUUID());
        execution.setTenantId("default");
        NonMovingStockExecutionOutput posted = output(execution, NonMovingStockExecutionOutputStatus.POSTED);
        NonMovingStockExecutionOutput pending = output(execution, NonMovingStockExecutionOutputStatus.PENDING);
        when(outputRepository.findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", execution.getId()
        )).thenReturn(List.of(posted, pending));
        when(executionRepository.save(execution)).thenReturn(execution);

        assertThat(executionService.synchronizeStatus(execution).getExecutionStatus())
            .isEqualTo(NonMovingStockExecutionStatus.PENDING.name());

        pending.setOutputStatus(NonMovingStockExecutionOutputStatus.POSTED.name());
        assertThat(executionService.synchronizeStatus(execution).getExecutionStatus())
            .isEqualTo(NonMovingStockExecutionStatus.EXECUTED.name());
    }

    @Test
    void postedTransferFinalizesEveryAuthoritativeMixedDecisionLineExactlyOnce() {
        UUID caseId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID detectionId = UUID.randomUUID();
        NonMovingStockExecution execution = new NonMovingStockExecution();
        execution.setId(UUID.randomUUID());
        execution.setTenantId("default");
        execution.setCompanyId(UUID.randomUUID());
        execution.setCaseId(caseId);
        execution.setDecisionId(decisionId);
        execution.setStatus(NonMovingStockExecutionStatus.PENDING.name());
        execution.setExecutionStatus(NonMovingStockExecutionStatus.PENDING.name());

        NonMovingStockExecutionOutput immediate = output(
            execution, NonMovingStockExecutionOutputStatus.POSTED
        );
        NonMovingStockExecutionOutput transfer = output(
            execution, NonMovingStockExecutionOutputStatus.PENDING
        );
        transfer.setId(UUID.randomUUID());
        transfer.setDocumentType("STOCK_MOVEMENT_DOCUMENT");
        transfer.setDocumentId(documentId);

        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setId(caseId);
        stockCase.setTenantId("default");
        stockCase.setCompanyId(execution.getCompanyId());
        stockCase.setStatus(NonMovingStockCaseStatus.IN_EXECUTION.name());

        NonMovingStockCaseLine caseLine = new NonMovingStockCaseLine();
        caseLine.setId(UUID.randomUUID());
        caseLine.setTenantId("default");
        caseLine.setCompanyId(execution.getCompanyId());
        caseLine.setCaseId(caseId);
        caseLine.setNonMovingStockId(detectionId);
        caseLine.setQuantity(new BigDecimal("10"));

        NonMovingStockDecisionLine transferLine = decisionLine(
            decisionId, caseLine.getId(), NonMovingStockDecisionType.TRANSFER, "4", 0
        );
        NonMovingStockDecisionLine writeOffLine = decisionLine(
            decisionId, caseLine.getId(), NonMovingStockDecisionType.WRITE_OFF, "6", 1
        );

        NonMovingStock detection = new NonMovingStock();
        detection.setId(detectionId);
        detection.setTenantId("default");
        detection.setCompanyId(execution.getCompanyId());
        detection.setCode("NMS-MIXED");
        detection.setName("Mixed pending detection");
        detection.setStatus(NonMovingStockStatus.IN_EXECUTION.name());
        detection.setQuantity(new BigDecimal("10"));

        when(outputRepository.findByTenantIdAndDocumentTypeAndDocumentIdAndIsDeletedFalse(
            "default", "STOCK_MOVEMENT_DOCUMENT", documentId
        )).thenReturn(List.of(transfer));
        when(executionRepository.findByIdAndIsDeletedFalse(execution.getId()))
            .thenReturn(Optional.of(execution));
        when(outputRepository.findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", execution.getId()
        )).thenReturn(List.of(immediate, transfer));
        when(executionRepository.save(execution)).thenReturn(execution);
        when(caseRepository.findByIdAndIsDeletedFalse(caseId)).thenReturn(Optional.of(stockCase));
        when(caseRepository.save(stockCase)).thenReturn(stockCase);
        when(decisionLineRepository.findByTenantIdAndDecisionIdAndIsDeletedFalseOrderByLineOrderAsc(
            "default", decisionId
        )).thenReturn(List.of(transferLine, writeOffLine));
        when(caseLineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", caseId
        )).thenReturn(List.of(caseLine));
        when(nonMovingStockRepository.findByIdAndIsDeletedFalse(detectionId))
            .thenReturn(Optional.of(detection));
        List<NonMovingStock> createdParts = new ArrayList<>();
        doAnswer(invocation -> {
            NonMovingStock saved = invocation.getArgument(0);
            if (saved != detection && saved.getId() == null) {
                saved.setId(UUID.randomUUID());
                createdParts.add(saved);
            }
            return saved;
        }).when(nonMovingStockRepository).save(any(NonMovingStock.class));

        executionService.reconcileDocumentOutput(
            "STOCK_MOVEMENT_DOCUMENT", documentId,
            NonMovingStockExecutionOutputStatus.POSTED, null, null
        );
        executionService.reconcileDocumentOutput(
            "STOCK_MOVEMENT_DOCUMENT", documentId,
            NonMovingStockExecutionOutputStatus.POSTED, null, null
        );

        assertThat(execution.getExecutionStatus()).isEqualTo(NonMovingStockExecutionStatus.EXECUTED.name());
        assertThat(stockCase.getStatus()).isEqualTo(NonMovingStockCaseStatus.EXECUTED.name());
        assertThat(detection.getQuantity()).isEqualByComparingTo("6");
        assertThat(detection.getStatus()).isEqualTo(NonMovingStockStatus.WRITE_OFF.name());
        assertThat(createdParts).singleElement().satisfies(part -> {
            assertThat(part.getQuantity()).isEqualByComparingTo("4");
            assertThat(part.getStatus()).isEqualTo(NonMovingStockStatus.TRANSFER.name());
            assertThat(part.getPlannedAction()).isEqualTo(NonMovingStockStatus.TRANSFER.name());
        });
    }

    @Test
    void lateCancellationCannotReopenAClosedCase() {
        UUID documentId = UUID.randomUUID();
        NonMovingStockExecution execution = new NonMovingStockExecution();
        execution.setId(UUID.randomUUID());
        execution.setTenantId("default");
        execution.setCompanyId(UUID.randomUUID());
        execution.setCaseId(UUID.randomUUID());
        execution.setStatus(NonMovingStockExecutionStatus.PENDING.name());
        execution.setExecutionStatus(NonMovingStockExecutionStatus.PENDING.name());
        NonMovingStockExecutionOutput transfer = output(
            execution, NonMovingStockExecutionOutputStatus.PENDING
        );
        transfer.setId(UUID.randomUUID());
        transfer.setDocumentType("STOCK_MOVEMENT_DOCUMENT");
        transfer.setDocumentId(documentId);
        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setId(execution.getCaseId());
        stockCase.setTenantId("default");
        stockCase.setCompanyId(execution.getCompanyId());
        stockCase.setStatus(NonMovingStockCaseStatus.CLOSED.name());

        when(outputRepository.findByTenantIdAndDocumentTypeAndDocumentIdAndIsDeletedFalse(
            "default", "STOCK_MOVEMENT_DOCUMENT", documentId
        )).thenReturn(List.of(transfer));
        when(executionRepository.findByIdAndIsDeletedFalse(execution.getId()))
            .thenReturn(Optional.of(execution));
        when(caseRepository.findByIdAndIsDeletedFalse(stockCase.getId()))
            .thenReturn(Optional.of(stockCase));
        assertThatThrownBy(() -> executionService.reconcileDocumentOutput(
            "STOCK_MOVEMENT_DOCUMENT", documentId,
            NonMovingStockExecutionOutputStatus.CANCELLED, "LATE_CANCEL", "Cancelled downstream"
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reopen");

        assertThat(stockCase.getStatus()).isEqualTo(NonMovingStockCaseStatus.CLOSED.name());
        assertThat(transfer.getOutputStatus()).isEqualTo(NonMovingStockExecutionOutputStatus.PENDING.name());
        verify(outputRepository, never()).save(any(NonMovingStockExecutionOutput.class));
        verify(caseRepository, never()).save(any(NonMovingStockCase.class));
    }

    @Test
    void legacyImpairmentReconcilesFromPostedOutputsOnlyAfterEveryDocumentPosts() {
        UUID caseId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID firstDocumentId = UUID.randomUUID();
        UUID secondDocumentId = UUID.randomUUID();
        NonMovingStockExecution execution = new NonMovingStockExecution();
        execution.setId(UUID.randomUUID());
        execution.setTenantId("default");
        execution.setCompanyId(UUID.randomUUID());
        execution.setCaseId(caseId);
        execution.setDecisionId(decisionId);
        execution.setStatus(NonMovingStockExecutionStatus.PENDING.name());
        execution.setExecutionStatus(NonMovingStockExecutionStatus.PENDING.name());
        NonMovingStockExecutionOutput firstOutput = output(
            execution, NonMovingStockExecutionOutputStatus.PENDING
        );
        firstOutput.setId(UUID.randomUUID());
        firstOutput.setDocumentType("STOCK_VALUATION_DOCUMENT");
        firstOutput.setDocumentId(firstDocumentId);
        firstOutput.setQuantity(new BigDecimal("4"));
        NonMovingStockExecutionOutput secondOutput = output(
            execution, NonMovingStockExecutionOutputStatus.PENDING
        );
        secondOutput.setId(UUID.randomUUID());
        secondOutput.setDocumentType("STOCK_VALUATION_DOCUMENT");
        secondOutput.setDocumentId(secondDocumentId);
        secondOutput.setQuantity(new BigDecimal("4"));
        NonMovingStockExecutionOutput integrationOutput = output(
            execution, NonMovingStockExecutionOutputStatus.POSTED
        );

        NonMovingStockCase stockCase = new NonMovingStockCase();
        stockCase.setId(caseId);
        stockCase.setTenantId("default");
        stockCase.setCompanyId(execution.getCompanyId());
        stockCase.setStatus(NonMovingStockCaseStatus.IN_EXECUTION.name());
        NonMovingStockCaseLine firstCaseLine = caseLine("4");
        firstCaseLine.setTenantId("default");
        firstCaseLine.setCompanyId(execution.getCompanyId());
        firstCaseLine.setCaseId(caseId);
        firstCaseLine.setNonMovingStockId(UUID.randomUUID());
        NonMovingStockCaseLine secondCaseLine = caseLine("4");
        secondCaseLine.setTenantId("default");
        secondCaseLine.setCompanyId(execution.getCompanyId());
        secondCaseLine.setCaseId(caseId);
        secondCaseLine.setNonMovingStockId(UUID.randomUUID());
        firstOutput.setCaseLineId(firstCaseLine.getId());
        secondOutput.setCaseLineId(secondCaseLine.getId());

        NonMovingStock firstDetection = detection(
            firstCaseLine.getNonMovingStockId(), execution.getCompanyId(), "LEGACY-IMP-1", "4"
        );
        NonMovingStock secondDetection = detection(
            secondCaseLine.getNonMovingStockId(), execution.getCompanyId(), "LEGACY-IMP-2", "4"
        );
        NonMovingStockDecision decision = new NonMovingStockDecision();
        decision.setId(decisionId);
        decision.setTenantId("default");
        decision.setCompanyId(execution.getCompanyId());
        decision.setDecisionType(NonMovingStockDecisionType.IMPAIRMENT_RESERVE.name());

        when(outputRepository.findByTenantIdAndDocumentTypeAndDocumentIdAndIsDeletedFalse(
            "default", "STOCK_VALUATION_DOCUMENT", firstDocumentId
        )).thenReturn(List.of(firstOutput));
        when(outputRepository.findByTenantIdAndDocumentTypeAndDocumentIdAndIsDeletedFalse(
            "default", "STOCK_VALUATION_DOCUMENT", secondDocumentId
        )).thenReturn(List.of(secondOutput));
        when(executionRepository.findByIdAndIsDeletedFalse(execution.getId()))
            .thenReturn(Optional.of(execution));
        when(caseRepository.findByIdAndIsDeletedFalse(caseId)).thenReturn(Optional.of(stockCase));
        when(outputRepository.findByTenantIdAndExecutionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", execution.getId()
        )).thenReturn(List.of(firstOutput, secondOutput, integrationOutput));
        when(executionRepository.save(execution)).thenReturn(execution);
        when(caseRepository.save(stockCase)).thenReturn(stockCase);
        when(decisionLineRepository.findByTenantIdAndDecisionIdAndIsDeletedFalseOrderByLineOrderAsc(
            "default", decisionId
        )).thenReturn(List.of());
        when(decisionRepository.findByIdAndIsDeletedFalse(decisionId)).thenReturn(Optional.of(decision));
        when(caseLineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", caseId
        )).thenReturn(List.of(firstCaseLine, secondCaseLine));
        when(nonMovingStockRepository.findByIdAndIsDeletedFalse(firstDetection.getId()))
            .thenReturn(Optional.of(firstDetection));
        when(nonMovingStockRepository.findByIdAndIsDeletedFalse(secondDetection.getId()))
            .thenReturn(Optional.of(secondDetection));
        when(nonMovingStockRepository.save(any(NonMovingStock.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        executionService.reconcileDocumentOutput(
            "STOCK_VALUATION_DOCUMENT", firstDocumentId,
            NonMovingStockExecutionOutputStatus.POSTED, null, null
        );

        assertThat(execution.getExecutionStatus()).isEqualTo(NonMovingStockExecutionStatus.PENDING.name());
        assertThat(stockCase.getStatus()).isEqualTo(NonMovingStockCaseStatus.IN_EXECUTION.name());
        assertThat(firstDetection.getStatus()).isEqualTo(NonMovingStockStatus.IN_EXECUTION.name());
        assertThat(secondDetection.getStatus()).isEqualTo(NonMovingStockStatus.IN_EXECUTION.name());

        executionService.reconcileDocumentOutput(
            "STOCK_VALUATION_DOCUMENT", secondDocumentId,
            NonMovingStockExecutionOutputStatus.POSTED, null, null
        );
        executionService.reconcileDocumentOutput(
            "STOCK_VALUATION_DOCUMENT", secondDocumentId,
            NonMovingStockExecutionOutputStatus.POSTED, null, null
        );

        assertThat(execution.getExecutionStatus()).isEqualTo(NonMovingStockExecutionStatus.EXECUTED.name());
        assertThat(stockCase.getStatus()).isEqualTo(NonMovingStockCaseStatus.EXECUTED.name());
        assertThat(firstDetection.getStatus()).isEqualTo(NonMovingStockStatus.IMPAIRMENT_RESERVE.name());
        assertThat(secondDetection.getStatus()).isEqualTo(NonMovingStockStatus.IMPAIRMENT_RESERVE.name());
        verify(nonMovingStockRepository, times(1)).save(firstDetection);
        verify(nonMovingStockRepository, times(1)).save(secondDetection);
    }

    private NonMovingStockCaseLine caseLine(String quantity) {
        NonMovingStockCaseLine line = new NonMovingStockCaseLine();
        line.setId(UUID.randomUUID());
        line.setTenantId("tenant-a");
        line.setCompanyId(UUID.randomUUID());
        line.setMaterialId(UUID.randomUUID());
        line.setWarehouseId(UUID.randomUUID());
        line.setStorageLocationId(UUID.randomUUID());
        line.setBatchId(UUID.randomUUID());
        line.setQuantity(new BigDecimal(quantity));
        line.setAvailableQuantity(new BigDecimal(quantity));
        return line;
    }

    private StockBalance balance(
        NonMovingStockCaseLine line,
        String quantity,
        String available,
        String reserved,
        String quarantine
    ) {
        StockBalance balance = new StockBalance();
        balance.setTenantId(line.getTenantId());
        balance.setCompanyId(line.getCompanyId());
        balance.setMaterialId(line.getMaterialId());
        balance.setWarehouseId(line.getWarehouseId());
        balance.setStorageLocationId(line.getStorageLocationId());
        balance.setStockBatchId(line.getBatchId());
        balance.setQuantity(new BigDecimal(quantity));
        balance.setAvailableQuantity(new BigDecimal(available));
        balance.setReservedQuantity(new BigDecimal(reserved));
        balance.setQuarantineQuantity(new BigDecimal(quarantine));
        return balance;
    }

    private NonMovingStock detection(UUID id, UUID companyId, String code, String quantity) {
        NonMovingStock detection = new NonMovingStock();
        detection.setId(id);
        detection.setTenantId("default");
        detection.setCompanyId(companyId);
        detection.setCode(code);
        detection.setName(code);
        detection.setStatus(NonMovingStockStatus.IN_EXECUTION.name());
        detection.setQuantity(new BigDecimal(quantity));
        return detection;
    }

    private NonMovingStockExecutionOutput output(
        NonMovingStockExecution execution,
        NonMovingStockExecutionOutputStatus status
    ) {
        NonMovingStockExecutionOutput output = new NonMovingStockExecutionOutput();
        output.setExecutionId(execution.getId());
        output.setOutputStatus(status.name());
        return output;
    }

    private NonMovingStockDecisionLine decisionLine(
        UUID decisionId,
        UUID caseLineId,
        NonMovingStockDecisionType type,
        String quantity,
        int order
    ) {
        NonMovingStockDecisionLine line = new NonMovingStockDecisionLine();
        line.setId(UUID.randomUUID());
        line.setTenantId("default");
        line.setDecisionId(decisionId);
        line.setCaseLineId(caseLineId);
        line.setDecisionType(type.name());
        line.setApprovedQuantity(new BigDecimal(quantity));
        line.setLineOrder(order);
        return line;
    }
}

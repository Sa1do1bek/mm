package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.InventoryAccountingReconciliation;
import com.enterprise.mm.entity.InventoryFxDifference;
import com.enterprise.mm.entity.InventoryPeriodClose;
import com.enterprise.mm.entity.LandedCostAllocation;
import com.enterprise.mm.entity.LandedCostAllocationLine;
import com.enterprise.mm.entity.StockValuationDocument;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.InventoryAccountingReconciliationMapper;
import com.enterprise.mm.mapper.InventoryFxDifferenceMapper;
import com.enterprise.mm.mapper.InventoryPeriodCloseMapper;
import com.enterprise.mm.mapper.LandedCostAllocationLineMapper;
import com.enterprise.mm.mapper.LandedCostAllocationMapper;
import com.enterprise.mm.mapper.StockValuationDocumentMapper;
import com.enterprise.mm.payload.InventoryAccountingReconciliationUpdateRequest;
import com.enterprise.mm.payload.InventoryFxDifferenceUpdateRequest;
import com.enterprise.mm.payload.InventoryPeriodCloseUpdateRequest;
import com.enterprise.mm.payload.LandedCostAllocationLineUpdateRequest;
import com.enterprise.mm.payload.LandedCostAllocationUpdateRequest;
import com.enterprise.mm.payload.StockValuationDocumentUpdateRequest;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.InventoryAccountingReconciliationRepository;
import com.enterprise.mm.repository.InventoryFxDifferenceRepository;
import com.enterprise.mm.repository.InventoryPeriodCloseRepository;
import com.enterprise.mm.repository.LandedCostAllocationLineRepository;
import com.enterprise.mm.repository.LandedCostAllocationRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockValuationDocumentRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryFinanceImmutabilityTest {
    private static final String TENANT = "tenant-inventory-finance-immutability";

    @Mock StockValuationDocumentRepository valuationRepository;
    @Mock StockValuationDocumentMapper valuationMapper;
    @Mock StockValuationService stockValuationService;
    @Mock InventoryValuationPolicyService valuationPolicyService;
    @Mock RoleDataScopeService roleDataScopeService;
    @Mock NonMovingStockExecutionService nonMovingStockExecutionService;

    @Mock LandedCostAllocationRepository landedCostRepository;
    @Mock LandedCostAllocationMapper landedCostMapper;
    @Mock LandedCostAllocationLineRepository landedCostLineRepository;
    @Mock LandedCostAllocationLineMapper landedCostLineMapper;
    @Mock StockBalanceRepository stockBalanceRepository;
    @Mock StockBatchRepository stockBatchRepository;
    @Mock ImportDeliveryRepository importDeliveryRepository;

    @Mock InventoryFxDifferenceRepository fxRepository;
    @Mock InventoryFxDifferenceMapper fxMapper;

    @Mock InventoryAccountingReconciliationRepository reconciliationRepository;
    @Mock InventoryAccountingReconciliationMapper reconciliationMapper;

    @Mock InventoryPeriodCloseRepository periodCloseRepository;
    @Mock InventoryPeriodCloseMapper periodCloseMapper;
    @Mock InventoryAccountingReconciliationService reconciliationService;
    @Mock InventoryValuationReconciliationService valuationReconciliationService;
    @Mock WarehouseRepository warehouseRepository;

    @Mock MaterialCatalogGuard materialCatalogGuard;
    @Mock InventoryCostingService inventoryCostingService;
    @Mock InventoryFinanceCalculationService calculationService;
    @Mock FinancialAuthorizationService financialAuthorizationService;
    @Mock IntegrationEventService integrationEventService;
    @Mock AuditService auditService;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @CsvSource(value = {
        "POSTED|POSTED valuation document is immutable; create a reversal or correction",
        "APPROVED|APPROVED valuation document is immutable; post or create a correction"
    }, delimiter = '|')
    void valuationUpdateRejectsProtectedPersistedStatusBeforeMapper(String status, String message) {
        StockValuationDocument entity = valuation(status);
        StockValuationDocumentUpdateRequest request = new StockValuationDocumentUpdateRequest();
        request.setStatus("DRAFT");
        request.setName("payload mutation");
        when(valuationRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            entity.setStatus(request.getStatus());
            entity.setName(request.getName());
            return null;
        }).when(valuationMapper).updateEntity(entity, request);
        lenient().when(valuationRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> valuationService().update(entity.getId(), request), message, entity, status, false);
        assertThat(entity.getName()).isEqualTo("original");
        verify(valuationMapper, never()).updateEntity(any(), any());
        verify(valuationRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource(value = {
        "POSTED|POSTED valuation document is immutable; create a reversal or correction",
        "APPROVED|APPROVED valuation document is immutable; post or create a correction"
    }, delimiter = '|')
    void valuationDeleteRejectsProtectedStatus(String status, String message) {
        StockValuationDocument entity = valuation(status);
        when(valuationRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(valuationRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> valuationService().softDelete(entity.getId()), message, entity, status, false);
        verify(valuationRepository, never()).save(any());
        verifyNoInteractions(valuationMapper);
    }

    @ParameterizedTest
    @CsvSource(value = {
        "POSTED|POSTED valuation document is immutable; create a reversal or correction",
        "APPROVED|APPROVED valuation document is immutable; post or create a correction"
    }, delimiter = '|')
    void valuationRestoreRejectsProtectedStatus(String status, String message) {
        StockValuationDocument entity = valuation(status);
        entity.softDelete();
        when(valuationRepository.findAnyById(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(valuationRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> valuationService().restore(entity.getId()), message, entity, status, true);
        verify(valuationRepository, never()).save(any());
        verifyNoInteractions(valuationMapper);
    }

    @Test
    void landedCostUpdateRejectsPostedPersistedStatusBeforeMapper() {
        LandedCostAllocation entity = landedCost("POSTED");
        LandedCostAllocationUpdateRequest request = new LandedCostAllocationUpdateRequest();
        request.setStatus("DRAFT");
        request.setName("payload mutation");
        when(landedCostRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            entity.setStatus(request.getStatus());
            entity.setName(request.getName());
            return null;
        }).when(landedCostMapper).updateEntity(entity, request);
        ImportDelivery delivery = scoped(new ImportDelivery(), "ACTIVE");
        delivery.setId(entity.getImportDeliveryId());
        lenient().when(importDeliveryRepository.findByIdAndIsDeletedFalse(entity.getImportDeliveryId())).thenReturn(Optional.of(delivery));
        lenient().when(landedCostRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> landedCostService().update(entity.getId(), request),
            "POSTED landed cost allocation is immutable; create a reversal or correction", entity, "POSTED", false);
        assertThat(entity.getName()).isEqualTo("original");
        verify(landedCostMapper, never()).updateEntity(any(), any());
        verify(landedCostRepository, never()).save(any());
    }

    @Test
    void landedCostDeleteRejectsPostedStatus() {
        LandedCostAllocation entity = landedCost("POSTED");
        when(landedCostRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(landedCostRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> landedCostService().softDelete(entity.getId()),
            "POSTED landed cost allocation is immutable; create a reversal or correction", entity, "POSTED", false);
        verify(landedCostRepository, never()).save(any());
        verifyNoInteractions(landedCostMapper);
    }

    @Test
    void landedCostRestoreRejectsPostedStatus() {
        LandedCostAllocation entity = landedCost("POSTED");
        entity.softDelete();
        when(landedCostRepository.findAnyById(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(landedCostRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> landedCostService().restore(entity.getId()),
            "POSTED landed cost allocation is immutable; create a reversal or correction", entity, "POSTED", true);
        verify(landedCostRepository, never()).save(any());
        verifyNoInteractions(landedCostMapper);
    }

    @Test
    void fxUpdateRejectsPostedPersistedStatusBeforeMapper() {
        InventoryFxDifference entity = fx("POSTED");
        InventoryFxDifferenceUpdateRequest request = new InventoryFxDifferenceUpdateRequest();
        request.setStatus("CALCULATED");
        request.setName("payload mutation");
        when(fxRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            entity.setStatus(request.getStatus());
            entity.setName(request.getName());
            return null;
        }).when(fxMapper).updateEntity(entity, request);
        lenient().when(fxRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> fxService().update(entity.getId(), request),
            "POSTED FX difference is immutable; create a reversal or correction", entity, "POSTED", false);
        assertThat(entity.getName()).isEqualTo("original");
        verify(fxMapper, never()).updateEntity(any(), any());
        verify(fxRepository, never()).save(any());
    }

    @Test
    void fxDeleteRejectsPostedStatus() {
        InventoryFxDifference entity = fx("POSTED");
        when(fxRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(fxRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> fxService().softDelete(entity.getId()),
            "POSTED FX difference is immutable; create a reversal or correction", entity, "POSTED", false);
        verify(fxRepository, never()).save(any());
        verifyNoInteractions(fxMapper);
    }

    @Test
    void fxRestoreRejectsPostedStatus() {
        InventoryFxDifference entity = fx("POSTED");
        entity.softDelete();
        when(fxRepository.findAnyById(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(fxRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> fxService().restore(entity.getId()),
            "POSTED FX difference is immutable; create a reversal or correction", entity, "POSTED", true);
        verify(fxRepository, never()).save(any());
        verifyNoInteractions(fxMapper);
    }

    @Test
    void reconciliationUpdateRejectsPostedPersistedStatusBeforeMapper() {
        InventoryAccountingReconciliation entity = reconciliation("POSTED");
        InventoryAccountingReconciliationUpdateRequest request = new InventoryAccountingReconciliationUpdateRequest();
        request.setStatus("DRAFT");
        request.setName("payload mutation");
        when(reconciliationRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            entity.setStatus(request.getStatus());
            entity.setName(request.getName());
            return null;
        }).when(reconciliationMapper).updateEntity(entity, request);
        lenient().when(reconciliationRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> reconciliationService().update(entity.getId(), request),
            "POSTED accounting reconciliation is immutable; create a correction", entity, "POSTED", false);
        assertThat(entity.getName()).isEqualTo("original");
        verify(reconciliationMapper, never()).updateEntity(any(), any());
        verify(reconciliationRepository, never()).save(any());
    }

    @Test
    void reconciliationDeleteRejectsPostedStatus() {
        InventoryAccountingReconciliation entity = reconciliation("POSTED");
        when(reconciliationRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(reconciliationRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> reconciliationService().softDelete(entity.getId()),
            "POSTED accounting reconciliation is immutable; create a correction", entity, "POSTED", false);
        verify(reconciliationRepository, never()).save(any());
        verifyNoInteractions(reconciliationMapper);
    }

    @Test
    void reconciliationRestoreRejectsPostedStatus() {
        InventoryAccountingReconciliation entity = reconciliation("POSTED");
        entity.softDelete();
        when(reconciliationRepository.findAnyById(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(reconciliationRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> reconciliationService().restore(entity.getId()),
            "POSTED accounting reconciliation is immutable; create a correction", entity, "POSTED", true);
        verify(reconciliationRepository, never()).save(any());
        verifyNoInteractions(reconciliationMapper);
    }

    @Test
    void periodCloseUpdateRejectsClosedPersistedStatusBeforeMapper() {
        InventoryPeriodClose entity = periodClose("CLOSED");
        InventoryPeriodCloseUpdateRequest request = new InventoryPeriodCloseUpdateRequest();
        request.setStatus("DRAFT");
        request.setName("payload mutation");
        when(periodCloseRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            entity.setStatus(request.getStatus());
            entity.setName(request.getName());
            return null;
        }).when(periodCloseMapper).updateEntity(entity, request);
        lenient().when(periodCloseRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> periodCloseService().update(entity.getId(), request),
            "CLOSED inventory period cannot be edited", entity, "CLOSED", false);
        assertThat(entity.getName()).isEqualTo("original");
        verify(periodCloseMapper, never()).updateEntity(any(), any());
        verify(periodCloseRepository, never()).save(any());
    }

    @Test
    void periodCloseDeleteRejectsClosedStatus() {
        InventoryPeriodClose entity = periodClose("CLOSED");
        when(periodCloseRepository.findByIdAndIsDeletedFalse(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(periodCloseRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> periodCloseService().softDelete(entity.getId()),
            "CLOSED inventory period cannot be deleted; use controlled reopen", entity, "CLOSED", false);
        verify(periodCloseRepository, never()).save(any());
        verifyNoInteractions(periodCloseMapper);
    }

    @Test
    void periodCloseRestoreRejectsClosedStatus() {
        InventoryPeriodClose entity = periodClose("CLOSED");
        entity.softDelete();
        when(periodCloseRepository.findAnyById(entity.getId())).thenReturn(Optional.of(entity));
        lenient().when(periodCloseRepository.save(entity)).thenReturn(entity);

        assertRejected(() -> periodCloseService().restore(entity.getId()),
            "CLOSED inventory period cannot be restored; use controlled reopen", entity, "CLOSED", true);
        verify(periodCloseRepository, never()).save(any());
        verifyNoInteractions(periodCloseMapper);
    }

    @Test
    void landedCostLineUpdateRejectsPostedCurrentParentBeforeMapper() {
        LandedCostAllocation currentParent = landedCost("POSTED");
        LandedCostAllocation targetParent = landedCost("DRAFT");
        LandedCostAllocationLine line = landedCostLine(currentParent.getId());
        LandedCostAllocationLineUpdateRequest request = new LandedCostAllocationLineUpdateRequest();
        request.setAllocationDocumentId(targetParent.getId());
        request.setName("payload mutation");
        when(landedCostLineRepository.findByIdAndIsDeletedFalse(line.getId())).thenReturn(Optional.of(line));
        lenient().when(landedCostRepository.findByIdAndIsDeletedFalse(currentParent.getId())).thenReturn(Optional.of(currentParent));
        lenient().when(landedCostRepository.findByIdAndIsDeletedFalse(targetParent.getId())).thenReturn(Optional.of(targetParent));
        lenient().doAnswer(invocation -> {
            line.setAllocationDocumentId(request.getAllocationDocumentId());
            line.setName(request.getName());
            return null;
        }).when(landedCostLineMapper).updateEntity(line, request);
        lenient().when(landedCostLineRepository.save(line)).thenReturn(line);

        assertRejected(() -> landedCostLineService().update(line.getId(), request),
            "Lines can be edited only for DRAFT landed cost allocation", line, "ACTIVE", false);
        assertThat(line.getAllocationDocumentId()).isEqualTo(currentParent.getId());
        assertThat(line.getName()).isEqualTo("original");
        verify(landedCostLineMapper, never()).updateEntity(any(), any());
        verify(landedCostRepository, never()).findByIdAndIsDeletedFalse(targetParent.getId());
        verify(landedCostLineRepository, never()).save(any());
    }

    @Test
    void landedCostLineDeleteRejectsPostedCurrentParent() {
        LandedCostAllocation parent = landedCost("POSTED");
        LandedCostAllocationLine line = landedCostLine(parent.getId());
        when(landedCostLineRepository.findByIdAndIsDeletedFalse(line.getId())).thenReturn(Optional.of(line));
        when(landedCostRepository.findByIdAndIsDeletedFalse(parent.getId())).thenReturn(Optional.of(parent));
        lenient().when(landedCostLineRepository.save(line)).thenReturn(line);

        assertRejected(() -> landedCostLineService().softDelete(line.getId()),
            "Lines can be edited only for DRAFT landed cost allocation", line, "ACTIVE", false);
        verify(landedCostLineRepository, never()).save(any());
        verifyNoInteractions(landedCostLineMapper);
    }

    @Test
    void landedCostLineRestoreRejectsPostedCurrentParent() {
        LandedCostAllocation parent = landedCost("POSTED");
        LandedCostAllocationLine line = landedCostLine(parent.getId());
        line.softDelete();
        when(landedCostLineRepository.findAnyById(line.getId())).thenReturn(Optional.of(line));
        when(landedCostRepository.findByIdAndIsDeletedFalse(parent.getId())).thenReturn(Optional.of(parent));
        lenient().when(landedCostLineRepository.save(line)).thenReturn(line);

        assertRejected(() -> landedCostLineService().restore(line.getId()),
            "Lines can be edited only for DRAFT landed cost allocation", line, "ACTIVE", true);
        verify(landedCostLineRepository, never()).save(any());
        verifyNoInteractions(landedCostLineMapper);
    }

    @Test
    void landedCostLineUpdateStillValidatesRequestedTargetParentAfterMapping() {
        LandedCostAllocation currentParent = landedCost("DRAFT");
        LandedCostAllocation targetParent = landedCost("POSTED");
        LandedCostAllocationLine line = landedCostLine(currentParent.getId());
        LandedCostAllocationLineUpdateRequest request = new LandedCostAllocationLineUpdateRequest();
        request.setAllocationDocumentId(targetParent.getId());
        when(landedCostLineRepository.findByIdAndIsDeletedFalse(line.getId())).thenReturn(Optional.of(line));
		lenient().when(landedCostRepository.findByIdAndIsDeletedFalse(currentParent.getId())).thenReturn(Optional.of(currentParent));
        when(landedCostRepository.findByIdAndIsDeletedFalse(targetParent.getId())).thenReturn(Optional.of(targetParent));
        lenient().doAnswer(invocation -> {
            line.setAllocationDocumentId(request.getAllocationDocumentId());
            return null;
        }).when(landedCostLineMapper).updateEntity(line, request);

        assertRejected(() -> landedCostLineService().update(line.getId(), request),
            "Lines can be edited only for DRAFT landed cost allocation", line, "ACTIVE", false);
        verify(landedCostLineMapper).updateEntity(line, request);
        verify(landedCostLineRepository, never()).save(any());
    }

    @Test
    void valuationMapperPreservesWorkflowOwnedFields() {
        StockValuationDocument entity = valuation("POSTED");
        LocalDateTime postedAt = LocalDateTime.of(2026, 6, 30, 18, 0);
        entity.setPostedAt(postedAt);
        StockValuationDocumentUpdateRequest request = new StockValuationDocumentUpdateRequest();
        request.setName("editable name");
        request.setStatus("DRAFT");
        request.setPostedAt(postedAt.plusDays(1));

        new StockValuationDocumentMapper().updateEntity(entity, request);

        assertThat(entity.getName()).isEqualTo("editable name");
        assertThat(entity.getStatus()).isEqualTo("POSTED");
        assertThat(entity.getPostedAt()).isEqualTo(postedAt);
    }

    @Test
    void landedCostMapperPreservesWorkflowOwnedFields() {
        LandedCostAllocation entity = landedCost("POSTED");
        LocalDateTime postedAt = LocalDateTime.of(2026, 6, 30, 18, 0);
        entity.setPostedAt(postedAt);
        entity.setBaseTotalAmount(new BigDecimal("125.50"));
        LandedCostAllocationUpdateRequest request = new LandedCostAllocationUpdateRequest();
        request.setName("editable name");
        request.setStatus("DRAFT");
        request.setPostedAt(postedAt.plusDays(1));
        request.setBaseTotalAmount(new BigDecimal("1.00"));

        new LandedCostAllocationMapper().updateEntity(entity, request);

        assertThat(entity.getName()).isEqualTo("editable name");
        assertThat(entity.getStatus()).isEqualTo("POSTED");
        assertThat(entity.getPostedAt()).isEqualTo(postedAt);
        assertThat(entity.getBaseTotalAmount()).isEqualByComparingTo("125.50");
    }

    @Test
    void fxMapperPreservesWorkflowOwnedFields() {
        InventoryFxDifference entity = fx("POSTED");
        LocalDateTime postedAt = LocalDateTime.of(2026, 6, 30, 18, 0);
        entity.setPostedAt(postedAt);
        entity.setBaseAmount(new BigDecimal("100"));
        entity.setRevaluedBaseAmount(new BigDecimal("110"));
        entity.setDifferenceAmount(new BigDecimal("10"));
        entity.setDifferenceType("LOSS");
        InventoryFxDifferenceUpdateRequest request = new InventoryFxDifferenceUpdateRequest();
        request.setName("editable name");
        request.setStatus("CALCULATED");
        request.setPostedAt(postedAt.plusDays(1));
        request.setBaseAmount(BigDecimal.ONE);
        request.setRevaluedBaseAmount(BigDecimal.ONE);
        request.setDifferenceAmount(BigDecimal.ZERO);
        request.setDifferenceType("NONE");

        new InventoryFxDifferenceMapper().updateEntity(entity, request);

        assertThat(entity.getName()).isEqualTo("editable name");
        assertThat(entity.getStatus()).isEqualTo("POSTED");
        assertThat(entity.getPostedAt()).isEqualTo(postedAt);
        assertThat(entity.getBaseAmount()).isEqualByComparingTo("100");
        assertThat(entity.getRevaluedBaseAmount()).isEqualByComparingTo("110");
        assertThat(entity.getDifferenceAmount()).isEqualByComparingTo("10");
        assertThat(entity.getDifferenceType()).isEqualTo("LOSS");
    }

    @Test
    void reconciliationMapperPreservesWorkflowOwnedFields() {
        InventoryAccountingReconciliation entity = reconciliation("POSTED");
        LocalDateTime reconciledAt = LocalDateTime.of(2026, 6, 30, 17, 0);
        LocalDateTime postedAt = reconciledAt.plusHours(1);
        entity.setReconciledAt(reconciledAt);
        entity.setPostedAt(postedAt);
        entity.setInventoryValue(new BigDecimal("1000"));
        entity.setDifferenceAmount(new BigDecimal("5"));
        entity.setDifferenceStatus("DIFFERENCE");
        InventoryAccountingReconciliationUpdateRequest request = new InventoryAccountingReconciliationUpdateRequest();
        request.setName("editable name");
        request.setStatus("DRAFT");
        request.setReconciledAt(reconciledAt.plusDays(1));
        request.setPostedAt(postedAt.plusDays(1));
        request.setInventoryValue(BigDecimal.ONE);
        request.setDifferenceAmount(BigDecimal.ZERO);
        request.setDifferenceStatus("MATCHED");

        new InventoryAccountingReconciliationMapper().updateEntity(entity, request);

        assertThat(entity.getName()).isEqualTo("editable name");
        assertThat(entity.getStatus()).isEqualTo("POSTED");
        assertThat(entity.getReconciledAt()).isEqualTo(reconciledAt);
        assertThat(entity.getPostedAt()).isEqualTo(postedAt);
        assertThat(entity.getInventoryValue()).isEqualByComparingTo("1000");
        assertThat(entity.getDifferenceAmount()).isEqualByComparingTo("5");
        assertThat(entity.getDifferenceStatus()).isEqualTo("DIFFERENCE");
    }

    @Test
    void periodCloseMapperPreservesWorkflowOwnedFields() {
        InventoryPeriodClose entity = periodClose("CLOSED");
        LocalDateTime closedAt = LocalDateTime.of(2026, 6, 30, 18, 0);
        UUID reconciliationId = UUID.randomUUID();
        entity.setClosedAt(closedAt);
        entity.setTotalStockValue(new BigDecimal("1000"));
        entity.setLandedCostAmount(new BigDecimal("100"));
        entity.setFxDifferenceAmount(new BigDecimal("10"));
        entity.setMovementCount(20);
        entity.setOpenMovementCount(2);
        entity.setValuationCount(5);
        entity.setAccountingReconciliationId(reconciliationId);
        entity.setAccountingReconciliationStatus("MATCHED");
        entity.setAccountingDifferenceAmount(new BigDecimal("3"));
        InventoryPeriodCloseUpdateRequest request = new InventoryPeriodCloseUpdateRequest();
        request.setName("editable name");
        request.setStatus("DRAFT");
        request.setClosedAt(closedAt.plusDays(1));
        request.setTotalStockValue(BigDecimal.ONE);
        request.setLandedCostAmount(BigDecimal.ONE);
        request.setFxDifferenceAmount(BigDecimal.ONE);
        request.setMovementCount(1);
        request.setOpenMovementCount(0);
        request.setValuationCount(1);
        request.setAccountingReconciliationId(UUID.randomUUID());
        request.setAccountingReconciliationStatus("DIFFERENCE");
        request.setAccountingDifferenceAmount(BigDecimal.ONE);

        new InventoryPeriodCloseMapper(warehouseRepository).updateEntity(entity, request);

        assertThat(entity.getName()).isEqualTo("editable name");
        assertThat(entity.getStatus()).isEqualTo("CLOSED");
        assertThat(entity.getClosedAt()).isEqualTo(closedAt);
        assertThat(entity.getTotalStockValue()).isEqualByComparingTo("1000");
        assertThat(entity.getLandedCostAmount()).isEqualByComparingTo("100");
        assertThat(entity.getFxDifferenceAmount()).isEqualByComparingTo("10");
        assertThat(entity.getMovementCount()).isEqualTo(20);
        assertThat(entity.getOpenMovementCount()).isEqualTo(2);
        assertThat(entity.getValuationCount()).isEqualTo(5);
        assertThat(entity.getAccountingReconciliationId()).isEqualTo(reconciliationId);
        assertThat(entity.getAccountingReconciliationStatus()).isEqualTo("MATCHED");
        assertThat(entity.getAccountingDifferenceAmount()).isEqualByComparingTo("3");
    }

    private void assertRejected(ThrowingCallable action, String message, BusinessEntity entity, String status, boolean deleted) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class).hasMessage(message);
        assertThat(entity.getStatus()).isEqualTo(status);
        assertThat(entity.isDeleted()).isEqualTo(deleted);
    }

    private StockValuationDocument valuation(String status) {
        StockValuationDocument entity = scoped(new StockValuationDocument(), status);
        entity.setMaterialId(UUID.randomUUID());
        entity.setWarehouseId(UUID.randomUUID());
        return entity;
    }

    private LandedCostAllocation landedCost(String status) {
        LandedCostAllocation entity = scoped(new LandedCostAllocation(), status);
        entity.setImportDeliveryId(UUID.randomUUID());
        entity.setDocumentDate(LocalDate.of(2026, 6, 30));
        return entity;
    }

    private LandedCostAllocationLine landedCostLine(UUID allocationId) {
        LandedCostAllocationLine entity = scoped(new LandedCostAllocationLine(), "ACTIVE");
        entity.setAllocationDocumentId(allocationId);
        entity.setMaterialId(UUID.randomUUID());
        entity.setWarehouseId(UUID.randomUUID());
        return entity;
    }

    private InventoryFxDifference fx(String status) {
        InventoryFxDifference entity = scoped(new InventoryFxDifference(), status);
        entity.setPeriodStart(LocalDate.of(2026, 6, 1));
        entity.setPeriodEnd(LocalDate.of(2026, 6, 30));
        return entity;
    }

    private InventoryAccountingReconciliation reconciliation(String status) {
        InventoryAccountingReconciliation entity = scoped(new InventoryAccountingReconciliation(), status);
        entity.setPeriodStart(LocalDate.of(2026, 6, 1));
        entity.setPeriodEnd(LocalDate.of(2026, 6, 30));
        return entity;
    }

    private InventoryPeriodClose periodClose(String status) {
        InventoryPeriodClose entity = scoped(new InventoryPeriodClose(), status);
        entity.setPeriodStart(LocalDate.of(2026, 6, 1));
        entity.setPeriodEnd(LocalDate.of(2026, 6, 30));
        return entity;
    }

    private <T extends BusinessEntity> T scoped(T entity, String status) {
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT);
        entity.setCompanyId(companyId);
        entity.setCode("CODE");
        entity.setName("original");
        entity.setStatus(status);
        return entity;
    }

    private StockValuationDocumentService valuationService() {
        return new StockValuationDocumentService(valuationRepository, valuationMapper, materialCatalogGuard,
            stockValuationService, inventoryCostingService, valuationPolicyService, financialAuthorizationService,
            auditService, roleDataScopeService, nonMovingStockExecutionService);
    }

    private LandedCostAllocationService landedCostService() {
        return new LandedCostAllocationService(landedCostRepository, landedCostMapper, landedCostLineRepository,
            stockBalanceRepository, stockBatchRepository, importDeliveryRepository, inventoryCostingService,
            financialAuthorizationService, integrationEventService, auditService, materialCatalogGuard);
    }

    private LandedCostAllocationLineService landedCostLineService() {
        return new LandedCostAllocationLineService(landedCostLineRepository, landedCostLineMapper,
            materialCatalogGuard, auditService, landedCostRepository);
    }

    private InventoryFxDifferenceService fxService() {
        return new InventoryFxDifferenceService(fxRepository, fxMapper, materialCatalogGuard,
            integrationEventService, auditService);
    }

    private InventoryAccountingReconciliationService reconciliationService() {
        return new InventoryAccountingReconciliationService(reconciliationRepository, reconciliationMapper,
            calculationService, integrationEventService, auditService,
            new InventoryPeriodCloseActionPolicy());
    }

    private InventoryPeriodCloseService periodCloseService() {
        return new InventoryPeriodCloseService(periodCloseRepository, periodCloseMapper, calculationService,
            reconciliationRepository, reconciliationService, valuationReconciliationService,
            financialAuthorizationService, integrationEventService, auditService,
            new InventoryPeriodCloseActionPolicy());
    }
}

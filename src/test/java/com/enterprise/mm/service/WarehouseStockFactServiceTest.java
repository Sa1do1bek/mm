package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class WarehouseStockFactServiceTest {
    @Mock RoleDataScopeService scope;
    @Mock WarehouseRepository warehouses;
    @Mock MaterialCatalogGuard catalog;
    @Mock WarehouseReceiptEligibilityService receiptEligibility;
    @Mock InventoryScopeLockService inventoryLocks;
    @Mock StockBalanceRepository balances;
    @Mock StockMovementDocumentRepository documents;
    @Mock StockMovementService movements;
    @Mock AttachmentService attachments;
    @Mock IdempotencyService idempotency;
    @Mock jakarta.persistence.EntityManager em;
    @Mock MaterialUnitRepository units;
    @Mock MaterialUnitEventRepository events;
    @Mock MaterialUnitEligibilityService unitEligibility;
    @Mock QualityInspectionService quality;
    @Mock AuditService audit;
    @Mock StockPickingAllocationService picking;
    @Mock MaterialStockAccountResolutionService materialAccounts;
    @Mock MaterialStockAccountRecommendationService accountRecommendations;
    @Mock ManualReceiptAccountDraftService accountDrafts;
    @InjectMocks WarehouseStockFactService service;
    final UUID companyId = UUID.randomUUID(), warehouseId = UUID.randomUUID(), materialId = UUID.randomUUID();
    final UUID positionId = UUID.randomUUID(), batchId = UUID.randomUUID();
    final MockMultipartFile proof = new MockMultipartFile("files", "proof.pdf", "application/pdf", new byte[] {1, 2, 3});
    Material material;

    @BeforeEach void setup() {
        TenantContext.setTenant("stock-fact-test"); CompanyContext.setCompany(companyId);
        var warehouse = new Warehouse(); warehouse.setId(warehouseId); warehouse.setCompanyId(companyId); warehouse.setStatus("ACTIVE");
        lenient().when(warehouses.findByTenantIdAndIdAndIsDeletedFalse("stock-fact-test", warehouseId)).thenReturn(Optional.of(warehouse));
        material = new Material(); material.setId(materialId); material.setCompanyId(companyId); material.setMaterialType(MaterialType.TMB);
        var unit = new UnitOfMeasure(); unit.setId(UUID.randomUUID()); unit.setCode("EA"); unit.setStockQuantityScale(0);
        material.setUnitOfMeasureId(unit.getId()); material.setUnitOfMeasure(unit);
        lenient().when(catalog.requireActiveTmbMaterial(materialId, "materialId")).thenReturn(material);
        var account = new MaterialStockAccountResolutionService.Account(UUID.randomUUID(), "1010", "Stock");
        var resolution = new MaterialStockAccountResolutionService.Resolution(materialId, 0L, true,
            "CONFIGURED_FALLBACK", account, account, "MATERIAL", List.of(), null, "fingerprint", List.of());
        lenient().when(materialAccounts.resolve(any())).thenReturn(resolution);
        lenient().when(materialAccounts.requireReceiptAccount(eq(resolution), isNull())).thenReturn(account);
        lenient().when(idempotency.executeRequired(anyString(), any(), anyString(), any(), eq(ManualWarehouseDocumentResponse.class), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(5)).get());
        lenient().when(documents.saveAndFlush(any())).thenAnswer(inv -> { var d = (StockMovementDocument) inv.getArgument(0); d.setId(UUID.randomUUID()); return d; });
        lenient().when(movements.postWarehouseStockFact(any())).thenAnswer(inv -> {
            var m = (StockMovement) inv.getArgument(0);
            var response = new StockMovementResponse(); response.setId(UUID.randomUUID()); response.setStockBatchId(batchId);
            response.setQuantity(m.getQuantity()); return response;
        });
    }
    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); }
    WarehouseStockFactRequest.Line line(UUID position, BigDecimal quantity) {
        return new WarehouseStockFactRequest.Line(materialId, quantity, new BigDecimal("50"), position, null, null);
    }
    WarehouseStockFactRequest request(WarehouseStockFactRequest.Line... lines) {
        return new WarehouseStockFactRequest(warehouseId, LocalDate.now().minusDays(1), "Workshop", "ACT-42", List.of(lines));
    }
    StockBalance position() {
        var position = new StockBalance(); position.setId(positionId); position.setTenantId("stock-fact-test"); position.setCompanyId(companyId);
        position.setWarehouseId(warehouseId); position.setMaterialId(materialId); position.setMaterial(material); position.setStockBatchId(batchId);
        position.setAvailableQuantity(new BigDecimal("5")); position.setStatus("ACTIVE");
        var batch = new StockBatch(); batch.setId(batchId); batch.setWarehouseId(warehouseId); batch.setMaterialId(materialId); batch.setStatus("ACTIVE");
        position.setStockBatch(batch);
        when(balances.findByIdAndIsDeletedFalseForUpdate(positionId)).thenReturn(Optional.of(position));
        return position;
    }
    @Test void receiptStoresProofBeforePostingAndKeepsDocumentHistory() {
        var result = service.record(request(line(null, new BigDecimal("2"))), List.of(proof), "receipt-1", true);
        assertThat(result.status()).isEqualTo("POSTED"); assertThat(result.lineCount()).isEqualTo(1);
        var order = inOrder(attachments, movements, documents);
        order.verify(documents).saveAndFlush(any());
        order.verify(attachments).uploadForCommand(eq("STOCK_MOVEMENT_DOCUMENT"), eq(result.documentId()), eq("DOCUMENT"), same(proof));
        var captured = ArgumentCaptor.forClass(StockMovement.class);
        order.verify(movements).postWarehouseStockFact(captured.capture());
        assertThat(captured.getValue().getReferenceId()).isEqualTo(result.documentId());
        assertThat(captured.getValue().getMovementType()).isEqualTo(MovementType.RECEIPT);
        assertThat(captured.getValue().getAccountCodeSnapshot()).isEqualTo("1010");
        assertThat(captured.getValue().getDescription()).contains("Workshop", "ACT-42");
        assertThat(captured.getValue().getMovementDate()).isEqualTo(LocalDate.now().minusDays(1));
        var document = ArgumentCaptor.forClass(StockMovementDocument.class);
        verify(documents).saveAndFlush(document.capture());
        assertThat(WarehouseStockFactMetadata.source(document.getValue().getManualOperationPayload(), null)).isEqualTo("Workshop");
    }
    @Test void aiReceiptVerifiesSavedDraftBeforePostingAndConsumesItAfterPosting() {
        UUID draftId = UUID.randomUUID(), jobId = UUID.randomUUID(), accountId = UUID.randomUUID();
        LocalDate date = LocalDate.now().minusDays(1);
        var account = new MaterialStockAccountResolutionService.Account(accountId, "1010", "Stock");
        var resolution = new MaterialStockAccountResolutionService.Resolution(materialId, 0L, true,
            "CHOICE_REQUIRED", null, null, null, List.of(account), date, "fingerprint", List.of());
        when(materialAccounts.resolve(any())).thenReturn(resolution);
        when(materialAccounts.requireReceiptAccount(resolution, accountId)).thenReturn(account);
        var line = new WarehouseStockFactRequest.Line(materialId, new BigDecimal("2"), new BigDecimal("50"),
            null, null, null, null, null, null, accountId, jobId, draftId, "Repair");

        service.record(request(line), List.of(proof), "ai-receipt-1", true);

        var order = inOrder(accountDrafts, accountRecommendations, movements);
        order.verify(accountDrafts).lockForPosting(draftId, warehouseId, materialId, date,
            "Repair", new BigDecimal("2"), new BigDecimal("50"));
        order.verify(accountRecommendations).verifyApplied(jobId, materialId, accountId, resolution, draftId);
        order.verify(movements).postWarehouseStockFact(any());
        order.verify(accountDrafts).markPosted(eq(draftId), any(UUID.class));
    }
    @Test void manualReceiptWithPurposeKeepsHistoryForFutureRecommendations() {
        var line = new WarehouseStockFactRequest.Line(materialId, new BigDecimal("2"), new BigDecimal("50"),
            null, null, null, null, null, null, null, null, null, "Repair");

        service.record(request(line), List.of(proof), "manual-purpose-1", true);

        verify(accountDrafts).recordPostedContext(eq(new ManualReceiptAccountDraftService.Create(
            warehouseId, materialId, LocalDate.now().minusDays(1), "Repair",
            new BigDecimal("2"), new BigDecimal("50"))), any(UUID.class));
    }
    @Test void issueUsesLockedStockPositionAndNormalStockPosting() {
        position();
        service.record(request(line(positionId, new BigDecimal("2"))), List.of(proof), "issue-1", false);
        var captured = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements).postWarehouseStockFact(captured.capture());
        assertThat(captured.getValue().getMovementType()).isEqualTo(MovementType.ISSUE);
        assertThat(captured.getValue().getStockBatchId()).isEqualTo(batchId);
        verify(scope).assertWarehouseAccess(warehouseId);
    }
    @Test void automaticIssuePostsEveryAllocatedBatchWithinOneDocument() {
        var first = position();
        var secondId = UUID.randomUUID();
        var secondBatch = UUID.randomUUID();
        var second = new StockBalance(); second.setId(secondId); second.setTenantId(TenantContext.currentTenant()); second.setCompanyId(companyId);
        second.setWarehouseId(warehouseId); second.setMaterialId(materialId); second.setMaterial(material); second.setStockBatchId(secondBatch);
        second.setAvailableQuantity(new BigDecimal("3")); second.setStatus("ACTIVE");
        var batch = new StockBatch(); batch.setId(secondBatch); batch.setWarehouseId(warehouseId); batch.setMaterialId(materialId); batch.setStatus("ACTIVE"); second.setStockBatch(batch);
        when(balances.findByIdAndIsDeletedFalseForUpdate(secondId)).thenReturn(Optional.of(second));
        var row = new WarehouseStockFactRequest.Line(materialId, new BigDecimal("8"), null, null, null, null, PickingMethod.FIFO, StockAllocationMode.AUTO, null);
        var allocations = List.of(
            new TransferPickingDtos.SelectedAllocation(positionId, first.getStockBatchId(), null, null, new BigDecimal("5"), List.of()),
            new TransferPickingDtos.SelectedAllocation(secondId, secondBatch, null, null, new BigDecimal("3"), List.of()));
        when(picking.allocateIssue(warehouseId, List.of(row), true)).thenReturn(List.of(new WarehouseStockFactPickingDtos.Line(materialId, PickingMethod.FIFO, StockAllocationMode.AUTO, new BigDecimal("8"), allocations)));
        var result = service.record(request(row), List.of(), "auto-issue", false);
        assertThat(result.movementIds()).hasSize(2);
        var posted = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements, times(2)).postWarehouseStockFact(posted.capture());
        assertThat(posted.getAllValues()).extracting(StockMovement::getStockBatchId).containsExactly(batchId, secondBatch);
        assertThat(posted.getAllValues()).extracting(StockMovement::getQuantity).containsExactly(new BigDecimal("5"), new BigDecimal("3"));
        verify(audit).log(eq("StockMovementDocument"), eq(result.documentId()), eq("ALLOCATE"), anyString(), isNull(), any());
    }
    @Test void receiptCanBeRecordedWithoutProof() {
        var result = service.record(request(line(null, BigDecimal.ONE)), null, "key", true);
        assertThat(result.status()).isEqualTo("POSTED");
        verify(movements).postWarehouseStockFact(any());
        verifyNoInteractions(attachments);
    }
    @Test void issueCanBeRecordedWithEmptyProofList() {
        position();
        var result = service.record(request(line(positionId, BigDecimal.ONE)), List.of(), "key", false);
        assertThat(result.status()).isEqualTo("POSTED");
        verify(movements).postWarehouseStockFact(any());
        verifyNoInteractions(attachments);
    }
    @Test void tooManyOrEmptyProofFilesAreStillRejected() {
        assertThatThrownBy(() -> service.record(request(line(null, BigDecimal.ONE)), Collections.nCopies(11, proof), "key", true))
            .hasMessageContaining("up to ten");
        var empty = new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> service.record(request(line(null, BigDecimal.ONE)), List.of(empty), "key", true))
            .hasMessageContaining("non-empty");
        verifyNoInteractions(documents, movements);
    }
    @Test void proofFailureNeverPostsStock() {
        when(attachments.uploadForCommand(anyString(), any(), anyString(), any())).thenThrow(new BusinessException("Storage unavailable"));
        assertThatThrownBy(() -> service.record(request(line(null, BigDecimal.ONE)), List.of(proof), "key", true)).hasMessage("Storage unavailable");
        verifyNoInteractions(movements);
    }
    @Test void unavailableStockIsRejectedBeforeSavingProof() {
        position();
        assertThatThrownBy(() -> service.record(request(line(positionId, new BigDecimal("6"))), List.of(proof), "key", false))
            .hasMessageContaining("Not enough available stock");
        verifyNoInteractions(attachments, movements, documents);
    }
    @ParameterizedTest
    @CsvSource({
        "TMB, false, stockPicking.insufficient",
        "TMB, true, stockPicking.unavailable",
        "FIXED_ASSET, false, stockPicking.insufficient",
        "FIXED_ASSET, true, stockPicking.unavailable"
    })
    void postingRechecksUseLocalizedStockPickingKeys(MaterialType type, boolean blocked, String key) {
        material.setMaterialType(type);
        if (type == MaterialType.FIXED_ASSET)
            when(catalog.requireActiveFixedAssetStockMaterial(materialId, "materialId")).thenReturn(material);
        var source = position();
        if (blocked) source.setStatus("BLOCKED");
        var command = request(line(positionId, blocked ? BigDecimal.ONE : new BigDecimal("6")));
        assertThatThrownBy(() -> {
            if (type == MaterialType.FIXED_ASSET) service.recordFixedAssets(command, List.of(proof), "recheck", false);
            else service.record(command, List.of(proof), "recheck", false);
        }).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo(key));
        verifyNoInteractions(attachments, movements, documents);
    }
    @Test void stockFromAnotherWarehouseIsRejected() {
        position().setWarehouseId(UUID.randomUUID());
        assertThatThrownBy(() -> service.record(request(line(positionId, BigDecimal.ONE)), List.of(proof), "key", false))
            .hasMessageContaining("your warehouse");
        verifyNoInteractions(attachments, movements);
    }
    @Test void thirdPartyStockCannotBeIssuedThroughManualFacts() {
        position().setOwnerPartyId(UUID.randomUUID());
        assertThatThrownBy(() -> service.record(request(line(positionId, BigDecimal.ONE)), List.of(proof), "key", false))
            .hasMessageContaining("company-owned");
        verifyNoInteractions(attachments, movements);
    }
    @Test void repeatedStockPositionCannotBypassAvailability() {
        position();
        assertThatThrownBy(() -> service.record(request(line(positionId, new BigDecimal("3")), line(positionId, new BigDecimal("3"))), List.of(proof), "key", false))
            .hasMessageContaining("duplicate");
        verifyNoInteractions(attachments, movements);
    }
    @Test void serializedReceiptsRequireEveryPhysicalSerial() {
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        assertThatThrownBy(() -> service.record(request(line(null, new BigDecimal("2"))), List.of(proof), "key", true))
            .hasMessageContaining("one serial number");
        verifyNoInteractions(attachments, movements);
    }
    @Test void invalidQuantitiesAndFutureDatesAreRejected() {
        assertThatThrownBy(() -> service.record(request(line(null, BigDecimal.ZERO)), List.of(proof), "key", true)).hasMessageContaining("positive quantity");
        var future = new WarehouseStockFactRequest(warehouseId, LocalDate.now().plusDays(1), null, null, List.of(line(null, BigDecimal.ONE)));
        assertThatThrownBy(() -> service.record(future, List.of(proof), "key", true)).hasMessageContaining("completed operation");
        verifyNoInteractions(attachments, movements);
    }
    @Test void warehouseLockPrecedesIdempotencyLookup() {
        service.record(request(line(null, BigDecimal.ONE)), List.of(proof), "key", true);
        var order = inOrder(inventoryLocks, idempotency);
        order.verify(inventoryLocks).lock(eq(companyId), eq(List.of(warehouseId)));
        order.verify(idempotency).executeRequired(anyString(), eq(warehouseId), eq("key"), any(), eq(ManualWarehouseDocumentResponse.class), any());
    }
    @Test void laterLineFailureRollsBackEarlierStockProofAndDocument() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:h2:mem:stock-fact-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("create table fact_documents (id uuid primary key)");
        jdbc.execute("create table fact_proofs (document_id uuid)");
        jdbc.execute("create table fact_stock (quantity decimal(22,7))");
        jdbc.update("insert into fact_stock values (0)");
        UUID secondId = UUID.randomUUID();
        var secondMaterial = new Material(); secondMaterial.setId(secondId); secondMaterial.setCompanyId(companyId);
        secondMaterial.setMaterialType(MaterialType.TMB); secondMaterial.setUnitOfMeasure(material.getUnitOfMeasure());
        secondMaterial.setUnitOfMeasureId(material.getUnitOfMeasureId());
        when(catalog.requireActiveTmbMaterial(secondId, "materialId")).thenReturn(secondMaterial);
        doAnswer(inv -> {
            var d = (StockMovementDocument) inv.getArgument(0); d.setId(UUID.randomUUID());
            jdbc.update("insert into fact_documents values (?)", d.getId()); return d;
        }).when(documents).saveAndFlush(any());
        when(attachments.uploadForCommand(anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            jdbc.update("insert into fact_proofs values (?)", inv.getArgument(1, UUID.class)); return null;
        });
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 2) throw new BusinessException("Second line failed");
            jdbc.update("update fact_stock set quantity = quantity + 1");
            var result = new StockMovementResponse(); result.setId(UUID.randomUUID()); return result;
        }).when(movements).postWarehouseStockFact(any());
        var proxy = new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactionalService = (WarehouseStockFactService) proxy.getProxy();
        var secondLine = new WarehouseStockFactRequest.Line(secondId, BigDecimal.ONE, BigDecimal.TEN, null, null, null);
        assertThatThrownBy(() -> transactionalService.record(request(line(null, BigDecimal.ONE), secondLine), List.of(proof), "key", true))
            .hasMessage("Second line failed");
        assertThat(jdbc.queryForObject("select count(*) from fact_documents", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from fact_proofs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select quantity from fact_stock", BigDecimal.class)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}

package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementDocument;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.payload.ManualWarehouseDocumentLineRequest;
import com.enterprise.mm.payload.ManualWarehouseDocumentRequest;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.MaterialUnitEventRepository;
import com.enterprise.mm.repository.MaterialUnitRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Exercises the service transaction and position lock through a real SQL connection. */
class ManualWarehouseBatchTransactionTest {
    private final UUID companyId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final UUID positionId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final UUID firstSpecId = UUID.randomUUID();
    private final UUID secondSpecId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private ManualWarehouseOperationService service;
    private StockMovementService movements;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:manual-batch-" + UUID.randomUUID() + ";MODE=PostgreSQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table batch_headers (id uuid primary key)");
        jdbc.execute("create table batch_movements (id uuid primary key)");
        jdbc.execute("create table batch_stock (id uuid primary key, available decimal(22,7) not null)");
        jdbc.update("insert into batch_stock (id, available) values (?, ?)", positionId, new BigDecimal("5"));

        var scope = mock(RoleDataScopeService.class);
        var warehouses = mock(WarehouseRepository.class);
        var catalog = mock(MaterialCatalogGuard.class);
        var balances = mock(StockBalanceRepository.class);
        movements = mock(StockMovementService.class);
        var idempotency = mock(IdempotencyService.class);
        var quality = mock(QualityInspectionService.class);
        var eligibility = mock(MaterialUnitEligibilityService.class);
        var units = mock(MaterialUnitRepository.class);
        var events = mock(MaterialUnitEventRepository.class);
        var contracts = mock(ContractRepository.class);
        var specifications = mock(ContractSpecificationRepository.class);
        var documents = mock(StockMovementDocumentRepository.class);

        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId); warehouse.setCompanyId(companyId);
        when(warehouses.findByTenantIdAndIdAndIsDeletedFalse("batch-transaction", warehouseId))
            .thenReturn(Optional.of(warehouse));
        Contract contract = new Contract();
        contract.setId(contractId); contract.setTenantId("batch-transaction");
        contract.setCompanyId(companyId); contract.setStatus("ACTIVE"); contract.setContractNumber("CT-1");
        when(contracts.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        for (UUID specId : List.of(firstSpecId, secondSpecId)) {
            ContractSpecification spec = new ContractSpecification();
            spec.setId(specId); spec.setTenantId("batch-transaction"); spec.setCompanyId(companyId);
            spec.setContractId(contractId); spec.setLineType(ProcurementLineType.MATERIAL); spec.setStatus("ACTIVE");
            spec.setMaterialId(materialId); spec.setQuantityLimit(new BigDecimal("10"));
            when(specifications.findByIdAndIsDeletedFalse(specId)).thenReturn(Optional.of(spec));
            when(specifications.findByIdAndIsDeletedFalseForUpdate(specId)).thenReturn(Optional.of(spec));
        }
        Material material = new Material();
        material.setId(materialId); material.setMaterialType(MaterialType.TMB); material.setName("Cable");
        UnitOfMeasure uom = new UnitOfMeasure(); uom.setId(UUID.randomUUID());
        uom.setCode("EA"); uom.setStockQuantityScale(0);
        material.setUnitOfMeasureId(uom.getId()); material.setUnitOfMeasure(uom);
        when(catalog.requireActiveTmbMaterial(materialId, "materialId")).thenReturn(material);
        when(balances.findByIdAndIsDeletedFalseForUpdate(positionId)).thenAnswer(invocation -> {
            BigDecimal available = jdbc.queryForObject(
                "select available from batch_stock where id = ? for update", BigDecimal.class, positionId);
            StockBalance balance = new StockBalance();
            balance.setId(positionId); balance.setTenantId("batch-transaction"); balance.setCompanyId(companyId);
            balance.setWarehouseId(warehouseId); balance.setMaterialId(materialId); balance.setMaterial(material);
            balance.setStatus("ACTIVE"); balance.setStockBatchId(batchId); balance.setAvailableQuantity(available);
            StockBatch batch = new StockBatch(); batch.setId(batchId); batch.setMaterialId(materialId);
            batch.setWarehouseId(warehouseId); batch.setStatus("ACTIVE"); balance.setStockBatch(batch);
            return Optional.of(balance);
        });
        when(balances.findWarehouseBalancesByMaterialIds(eq("batch-transaction"), eq(companyId), eq(warehouseId), anyList()))
            .thenAnswer(invocation -> {
                var summary = mock(StockBalanceRepository.WarehouseMaterialBalanceSummaryProjection.class);
                when(summary.getMaterialId()).thenReturn(materialId);
                when(summary.getTotalAvailable()).thenReturn(jdbc.queryForObject(
                    "select available from batch_stock where id = ?", BigDecimal.class, positionId));
                return List.of(summary);
            });
        when(documents.save(any())).thenAnswer(invocation -> {
            StockMovementDocument document = invocation.getArgument(0);
            if (document.getId() == null) {
                document.setId(UUID.randomUUID());
                jdbc.update("insert into batch_headers (id) values (?)", document.getId());
            }
            return document;
        });
        when(idempotency.executeRequired(anyString(), any(), anyString(), any(), any(), any()))
            .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());

        var approvalEngine = mock(com.enterprise.mm.service.approval.ApprovalEngineService.class);
        @SuppressWarnings("unchecked")
        var approvalProvider = (org.springframework.beans.factory.ObjectProvider<com.enterprise.mm.service.approval.ApprovalEngineService>)
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(approvalProvider.getObject()).thenReturn(approvalEngine);

        var target = new ManualWarehouseOperationService(scope, warehouses, catalog, balances, movements,
            idempotency, quality, eligibility, units, events, contracts,
            mock(com.enterprise.mm.repository.SupplierRepository.class), specifications, documents,
            mock(StockMovementRepository.class), mock(ContractGroupReceiptService.class),
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
            approvalProvider, mock(InventoryScopeLockService.class), mock(jakarta.persistence.EntityManager.class),
            mock(com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService.class),
            mock(ContractReceiptLimitService.class), mock(WarehouseReceiptProfileService.class),
            mock(WarehouseReceiptEligibilityService.class),
            mock(com.enterprise.mm.warehouse.packaging.ReturnablePackagingService.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
            new AnnotationTransactionAttributeSource()));
        service = (ManualWarehouseOperationService) proxy.getProxy();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear(); CompanyContext.clear();
    }

    private void context() {
        TenantContext.setTenant("batch-transaction"); CompanyContext.setCompany(companyId);
    }

    private ManualWarehouseDocumentRequest receipt() {
        return new ManualWarehouseDocumentRequest(warehouseId, contractId, LocalDate.now(), null, null, List.of(
            new ManualWarehouseDocumentLineRequest(firstSpecId, BigDecimal.ONE, BigDecimal.TEN, null, null, null, null),
            new ManualWarehouseDocumentLineRequest(secondSpecId, BigDecimal.ONE, BigDecimal.TEN, null, null, null, null)));
    }

    private ManualWarehouseDocumentRequest issue(String quantity) {
        return new ManualWarehouseDocumentRequest(warehouseId, contractId, LocalDate.now(), null, null, List.of(
            new ManualWarehouseDocumentLineRequest(firstSpecId, new BigDecimal(quantity), null, positionId, null, null, null)));
    }

    @Test
    void failureOnLaterDraftLineRollsBackHeaderAndEarlierDraft() {
        context();
        when(movements.createManualWarehouseDraft(any())).thenAnswer(invocation -> {
            if (jdbc.queryForObject("select count(*) from batch_movements", Integer.class) > 0) {
                throw new IllegalStateException("late line failed");
            }
            UUID movementId = UUID.randomUUID();
            jdbc.update("insert into batch_movements (id) values (?)", movementId);
            StockMovementResponse response = new StockMovementResponse(); response.setId(movementId);
            return response;
        });

        assertThatThrownBy(() -> service.receiptBatch(receipt(), "rollback-key"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("late line failed");
        assertThat(jdbc.queryForObject("select count(*) from batch_headers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from batch_movements", Integer.class)).isZero();
    }

    @Test
    void concurrentIssueSubmissionsDoNotChangeStockBeforeApproval() throws Exception {
        when(movements.createManualWarehouseDraft(any())).thenAnswer(invocation -> {
            UUID movementId = UUID.randomUUID();
            jdbc.update("insert into batch_movements (id) values (?)", movementId);
            StockMovementResponse response = new StockMovementResponse(); response.setId(movementId);
            return response;
        });
        var start = new CountDownLatch(1);
        Callable<Boolean> command = () -> {
            context();
            try {
                start.await();
                service.issueBatch(issue("4"), UUID.randomUUID().toString());
                return true;
            } catch (Exception failure) {
                return false;
            } finally {
                TenantContext.clear(); CompanyContext.clear();
            }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = workers.submit(command);
            Future<Boolean> second = workers.submit(command);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactly(true, true);
        }
        assertThat(jdbc.queryForObject("select available from batch_stock where id = ?", BigDecimal.class, positionId))
            .isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("select count(*) from batch_headers", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from batch_movements", Integer.class)).isEqualTo(2);
    }
}

package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Run the same receipt and immutable-import contracts against the deployed SQL dialect and Flyway DDL. */
@Testcontainers
@EntityScan(basePackages = {"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class ContractMaterialLinkPostgresTest extends ContractMaterialLinkIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryScopeLockService scopeLocks;
    @Autowired ExactOpeningImportGuard openingGuard;
    @Autowired InventoryFreezeGuardService freezeGuard;
    @Autowired com.enterprise.mm.repository.MaterialRepository materials;

    @Test void concurrentMixedBatchesLockAllMaterialsInOneOrder() throws Exception {
        migration.importChunk(tenant, company, command(row()));
        var ids = tx.execute(status -> {
            var x = importedMaterial("3".repeat(32), "lock-order-x", stockGroup, stockUnit);
            var secondContract = new com.enterprise.mm.entity.Contract();
            secondContract.setSupplierId(em.find(com.enterprise.mm.entity.Contract.class, contract).getSupplierId());
            persist(secondContract);
            var linkedX = new com.enterprise.mm.entity.ContractSpecification();
            linkedX.setContractId(secondContract.getId()); linkedX.setMaterialGroupId(group);
            linkedX.setUnitOfMeasureId(contractUnit); linkedX.setUnit("т");
            linkedX.setQuantityLimit(java.math.BigDecimal.TEN); linkedX.setPrice(java.math.BigDecimal.ONE);
            linkedX.setCode("1C-DOCUMENT9197MATERIALS-" + "4".repeat(32));
            linkedX.setDescription(marker(ORIGINAL, "Document9197Materials", "4".repeat(32))); persist(linkedX);
            return java.util.List.of(x.getId(), secondContract.getId(), linkedX.getId(),
                directSpecification(contract, x.getId()), directSpecification(secondContract.getId(), material),
                persist(new com.enterprise.mm.entity.Warehouse()).getId(), persist(new com.enterprise.mm.entity.Warehouse()).getId());
        });
        var secondPayload = payload(row());
        secondPayload.put("materialId", ids.get(0).toString()).put("materialSourceId", "3".repeat(32))
            .put("expectedMaterialCode", "lock-order-x").put("expectedContractId", ids.get(1).toString());
        migration.importChunk(tenant, company, command(new com.enterprise.mm.payload.CatalogMasterMigrationDtos.MasterRow(
            "4".repeat(32), "Document9197Materials", ids.get(2), secondPayload)));
        var firstBatch = new com.enterprise.mm.payload.ManualWarehouseDocumentRequest(ids.get(5), contract,
            java.time.LocalDate.now(), null, null, java.util.List.of(batchLine(ids.get(3), null), batchLine(specification, material)));
        var secondBatch = new com.enterprise.mm.payload.ManualWarehouseDocumentRequest(ids.get(6), ids.get(1),
            java.time.LocalDate.now(), null, null, java.util.List.of(batchLine(ids.get(2), ids.get(0)), batchLine(ids.get(4), null)));
        var pids = new java.util.concurrent.ConcurrentHashMap<java.util.UUID, Integer>();
        var postingStarted = java.util.concurrent.ConcurrentHashMap.<java.util.UUID>newKeySet();
        doAnswer(invocation -> {
            com.enterprise.mm.entity.StockMovement movement = invocation.getArgument(0);
            openingGuard.assertOperationsAllowed(company);
            freezeGuard.ensureMovementAllowed(movement);
            var ownContract = movement.getReferenceId();
            var otherContract = ownContract.equals(contract) ? ids.get(1) : contract;
            if (postingStarted.add(ownContract)) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                boolean peerReady = false;
                while (System.nanoTime() < deadline) {
                    Integer otherPid = pids.get(otherContract);
                    peerReady = postingStarted.contains(otherContract) || (otherPid != null && Boolean.TRUE.equals(
                        jdbc.queryForObject("select ? = any(pg_blocking_pids(?))", Boolean.class, pids.get(ownContract), otherPid)));
                    if (peerReady) break;
                    Thread.sleep(10);
                }
                assertThat(peerReady).as("peer reaches posting or waits for the material locks held by this batch").isTrue();
            }
            // Exercise the real PostgreSQL canonical lock used by first balance creation in StockMovementService.
            materials.lockForStockBalanceCreation(tenant, company, movement.getMaterialId()).orElseThrow();
            persist(movement).setStatus("POSTED"); em.flush(); return movementMapper.toResponse(movement);
        }).when(posting).postManualWarehouseMovement(any());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.Stream.of(firstBatch, secondBatch).map(batch -> executor.submit(() -> {
                com.enterprise.mm.common.TenantContext.setTenant(tenant); com.enterprise.mm.common.CompanyContext.setCompany(company);
                try {
                    return tx.execute(status -> {
                        jdbc.execute("set local lock_timeout = '8s'");
                        pids.put(batch.contractId(), jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                        return manual.receiptBatch(batch, "mixed-material-lock-order-" + batch.contractId());
                    });
                } finally { com.enterprise.mm.common.TenantContext.clear(); com.enterprise.mm.common.CompanyContext.clear(); }
            })).toList();
            for (var future : futures) assertThat(future.get(15, java.util.concurrent.TimeUnit.SECONDS).lineCount()).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where tenant_id=?", Long.class, tenant)).isEqualTo(4L);
    }

    private java.util.UUID directSpecification(java.util.UUID contractId, java.util.UUID materialId) {
        var spec = new com.enterprise.mm.entity.ContractSpecification();
        spec.setContractId(contractId); spec.setMaterialId(materialId);
        spec.setMaterialTypeSnapshot(com.enterprise.mm.enums.MaterialType.TMB);
        spec.setQuantityLimit(java.math.BigDecimal.TEN); spec.setPrice(java.math.BigDecimal.ONE);
        return persist(spec).getId();
    }

    private com.enterprise.mm.payload.ManualWarehouseDocumentLineRequest batchLine(java.util.UUID spec, java.util.UUID selectedMaterial) {
        return new com.enterprise.mm.payload.ManualWarehouseDocumentLineRequest(spec, java.math.BigDecimal.ONE,
            java.math.BigDecimal.ONE, null, selectedMaterial, null, null);
    }

    @Test void manualReceiptAcquiresWarehouseScopeBeforeMaterialLocks() throws Exception {
        migration.importChunk(tenant, company, command(row()));
        java.util.UUID warehouse = tx.execute(status -> persist(new com.enterprise.mm.entity.Warehouse()).getId());
        doAnswer(invocation -> {
            com.enterprise.mm.entity.StockMovement movement = invocation.getArgument(0);
            // Keep the real opening/freeze/canonical material lock sequence from stock posting.
            openingGuard.assertOperationsAllowed(company);
            freezeGuard.ensureMovementAllowed(movement);
            materials.lockForStockBalanceCreation(tenant, company, movement.getMaterialId()).orElseThrow();
            persist(movement).setStatus("POSTED"); em.flush(); return movementMapper.toResponse(movement);
        }).when(posting).postManualWarehouseMovement(any());
        var pid = new java.util.concurrent.atomic.AtomicInteger();
        var receiptStarted = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Object>>();
            tx.executeWithoutResult(status -> {
                jdbc.execute("set local lock_timeout = '8s'");
                int ordinaryPid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                var ordinaryReceipt = new com.enterprise.mm.entity.StockMovement();
                ordinaryReceipt.setCompanyId(company); ordinaryReceipt.setWarehouseId(warehouse);
                ordinaryReceipt.setMaterialId(material); ordinaryReceipt.setMovementType(com.enterprise.mm.enums.MovementType.RECEIPT);
                freezeGuard.ensureMovementAllowed(ordinaryReceipt);
                future.set(executor.submit(() -> {
                    com.enterprise.mm.common.TenantContext.setTenant(tenant); com.enterprise.mm.common.CompanyContext.setCompany(company);
                    try {
                        return tx.execute(inner -> {
                            jdbc.execute("set local lock_timeout = '8s'");
                            pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); receiptStarted.countDown();
                            return manual.receiptBatch(new com.enterprise.mm.payload.ManualWarehouseDocumentRequest(warehouse, contract,
                                java.time.LocalDate.now(), null, null, java.util.List.of(batchLine(specification, material))), "warehouse-lock-order");
                        });
                    } finally { com.enterprise.mm.common.TenantContext.clear(); com.enterprise.mm.common.CompanyContext.clear(); }
                }));
                try {
                    assertThat(receiptStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    boolean waiting = false;
                    while (System.nanoTime() < deadline) {
                        waiting = Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",
                            Boolean.class, ordinaryPid, pid.get()));
                        if (waiting) break;
                        Thread.sleep(10);
                    }
                    assertThat(waiting).as("manual receipt waits for the ordinary receipt's warehouse scope").isTrue();
                    // The ordinary receipt must still acquire its canonical material lock without a cycle.
                    assertThat(materials.lockForStockBalanceCreation(tenant, company, material)).isPresent();
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            });
            assertThat(future.get().get(15, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void receiptAcquiresCompanyGuardBeforeSpecificationLock(boolean batch) throws Exception {
        migration.importChunk(tenant, company, command(row()));
        java.util.UUID warehouse = tx.execute(status -> persist(new com.enterprise.mm.entity.Warehouse()).getId());
        doAnswer(invocation -> {
            // Retain the real posting guard while isolating inventory valuation in this focused test.
            openingGuard.assertOperationsAllowed(company);
            com.enterprise.mm.entity.StockMovement movement = invocation.getArgument(0);
            persist(movement).setStatus("POSTED"); em.flush(); return movementMapper.toResponse(movement);
        }).when(posting).postManualWarehouseMovement(any());
        var pid = new java.util.concurrent.atomic.AtomicInteger();
        var receiptStarted = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Object>>();
            tx.executeWithoutResult(status -> {
                // The migration holds this exclusive company lock before locking its specification.
                scopeLocks.lock(company, java.util.List.of());
                future.set(executor.submit(() -> {
                    com.enterprise.mm.common.TenantContext.setTenant(tenant); com.enterprise.mm.common.CompanyContext.setCompany(company);
                    try {
                        return tx.execute(inner -> {
                            pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); receiptStarted.countDown();
                            var line = new com.enterprise.mm.payload.ManualWarehouseDocumentLineRequest(specification, java.math.BigDecimal.TEN,
                                java.math.BigDecimal.ONE, null, material, null, null);
                            return batch ? manual.receiptBatch(new com.enterprise.mm.payload.ManualWarehouseDocumentRequest(warehouse, contract,
                                java.time.LocalDate.now(), null, null, java.util.List.of(line)), "lock-batch")
                                : manual.receipt(new com.enterprise.mm.payload.ManualWarehouseOperationRequest(warehouse, material,
                                    java.math.BigDecimal.TEN, java.math.BigDecimal.ONE, null, java.time.LocalDate.now(), null, null,
                                    "UZS", null, null, contract, specification), "lock-single");
                        });
                    } finally { com.enterprise.mm.common.TenantContext.clear(); com.enterprise.mm.common.CompanyContext.clear(); }
                }));
                try {
                    assertThat(receiptStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    boolean waiting = false;
                    while (System.nanoTime() < deadline) {
                        waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                            "select exists(select 1 from pg_locks where pid=? and locktype='advisory' and not granted)", Boolean.class, pid.get()));
                        if (waiting) break;
                        Thread.sleep(10);
                    }
                    assertThat(waiting).as("receipt is waiting for the migration's company lock").isTrue();
                    assertThat(jdbc.queryForObject("select id from contract_specifications where id=? for update nowait", java.util.UUID.class, specification))
                        .as("waiting receipt must not already hold the specification row").isEqualTo(specification);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            });
            assertThat(future.get().get(15, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        }
    }


    @Test void databaseRejectsMutationOrDeletionOfAStoredFactor() {
        migration.importChunk(tenant, company, command(row()));
        assertThatThrownBy(() -> jdbc.update("update contract_material_links set contract_quantity_per_stock_quantity=1 where specification_id=?", specification))
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from contract_material_links where specification_id=?", specification))
            .hasMessageContaining("immutable");
    }

    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }
}

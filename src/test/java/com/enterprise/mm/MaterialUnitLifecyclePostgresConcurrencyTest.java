package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.enums.MaterialUnitEventType;
import com.enterprise.mm.enums.MaterialUnitOperationType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.repository.MaterialUnitEventRepository;
import com.enterprise.mm.repository.MaterialUnitOperationRepository;
import com.enterprise.mm.repository.MaterialUnitRepository;
import com.enterprise.mm.service.MaterialUnitEligibilityService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MaterialUnitLifecyclePostgresConcurrencyTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired MaterialUnitEligibilityService service;
    @Autowired MaterialUnitRepository units;
    @Autowired MaterialUnitEventRepository events;
    @Autowired MaterialUnitOperationRepository operations;
    @Autowired PlatformTransactionManager transactionManager;

    private final String tenantId = "wave2-concurrency";
    private final UUID companyId = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final UUID warehouseA = UUID.randomUUID();
    private final UUID warehouseB = UUID.randomUUID();
    private final UUID locationA = UUID.randomUUID();
    private final UUID locationB = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void concurrentIssueOfSameUnitSucceedsOnce() throws Exception {
        UUID unitId = saveUnit("SN-ISSUE-RACE", MaterialUnitStatus.IN_STOCK, warehouseA, locationA);
        UUID issueId = UUID.randomUUID();

        List<Throwable> failures = race(2, () -> inTransaction(() -> {
            var selected = service.lockAndValidate(
                materialId,
                warehouseA,
                locationA,
                null,
                BigDecimal.ONE,
                List.of(unitId),
                MaterialUnitStatus.IN_STOCK
            );
            service.markIssued(selected, "IssueRequest", issueId, UUID.randomUUID(), warehouseA, locationA, null);
            return null;
        }));

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).hasMessageContaining("MATERIAL_UNIT_ALREADY_ISSUED");
        assertUnit(unitId, MaterialUnitStatus.ISSUED, warehouseA, locationA);
        assertLifecycleRows(unitId, MaterialUnitOperationType.ISSUE, MaterialUnitEventType.ISSUED, 1);
    }

    @Test
    void concurrentTransferDispatchOfSameUnitSucceedsOnce() throws Exception {
        UUID unitId = saveUnit("SN-TRANSFER-RACE", MaterialUnitStatus.IN_STOCK, warehouseA, locationA);
        UUID transferId = UUID.randomUUID();

        List<Throwable> failures = race(2, () -> inTransaction(() -> {
            service.dispatchTransfer(
                materialId,
                warehouseA,
                locationA,
                null,
                BigDecimal.ONE,
                List.of(unitId),
                "TransferDocument",
                transferId,
                UUID.randomUUID()
            );
            return null;
        }));

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).hasMessageContaining("MATERIAL_UNIT_IN_TRANSIT");
        assertUnit(unitId, MaterialUnitStatus.IN_TRANSIT, warehouseA, locationA);
        assertLifecycleRows(unitId, MaterialUnitOperationType.TRANSFER, MaterialUnitEventType.TRANSFER_DISPATCHED, 1);
    }

    @Test
    void concurrentReturnOfSameIssuedUnitSucceedsOnce() throws Exception {
        UUID unitId = saveUnit("SN-RETURN-RACE", MaterialUnitStatus.ISSUED, warehouseA, locationA);
        UUID returnId = UUID.randomUUID();

        List<Throwable> failures = race(2, () -> inTransaction(() -> {
            var selected = service.lockAndValidate(
                materialId,
                warehouseA,
                null,
                null,
                BigDecimal.ONE,
                List.of(unitId),
                MaterialUnitStatus.ISSUED
            );
            service.markReturned(selected, "MaterialReturn", returnId, UUID.randomUUID(), warehouseA, locationA, null);
            return null;
        }));

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).hasMessageContaining("MATERIAL_UNIT_NOT_AVAILABLE");
        assertUnit(unitId, MaterialUnitStatus.IN_STOCK, warehouseA, locationA);
        assertLifecycleRows(unitId, MaterialUnitOperationType.RETURN, MaterialUnitEventType.RETURNED, 1);
    }

    @Test
    void transferReceivePreservesIdentityAndDestination() {
        UUID unitId = saveUnit("SN-TRANSFER-RECEIVE", MaterialUnitStatus.IN_TRANSIT, warehouseA, locationA);
        UUID transferId = UUID.randomUUID();

        inTransaction(() -> {
            service.receiveTransfer(
                materialId,
                warehouseA,
                warehouseB,
                locationB,
                null,
                BigDecimal.ONE,
                List.of(unitId),
                "TransferDocument",
                transferId,
                UUID.randomUUID()
            );
            return null;
        });

        assertUnit(unitId, MaterialUnitStatus.IN_STOCK, warehouseB, locationB);
        assertLifecycleRows(unitId, MaterialUnitOperationType.TRANSFER, MaterialUnitEventType.TRANSFER_RECEIVED, 1);
    }

    private UUID saveUnit(String serial, MaterialUnitStatus status, UUID warehouseId, UUID locationId) {
        return inTransaction(() -> {
            setContext();
            MaterialUnit unit = new MaterialUnit();
            unit.setTenantId(tenantId);
            unit.setCompanyId(companyId);
            unit.setName(serial);
            unit.setStatus(status.name());
            unit.setMaterialId(materialId);
            unit.setManufacturerSerialNumber(serial);
            unit.setNormalizedSerialNumber(serial);
            unit.setInternalUnitNumber("IU-" + serial);
            unit.setUnitStatus(status);
            unit.setWarehouseId(warehouseId);
            unit.setStorageLocationId(locationId);
            unit.setStockBatchId(batchId);
            unit.setGoodsReceiptLineId(UUID.randomUUID());
            unit.setGoodsReceiptUnitId(UUID.randomUUID());
            unit.setReceivedAt(LocalDateTime.now());
            return units.saveAndFlush(unit).getId();
        });
    }

    private void assertUnit(UUID unitId, MaterialUnitStatus status, UUID warehouseId, UUID locationId) {
        MaterialUnit unit = inTransaction(() -> {
            setContext();
            return units.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, unitId).orElseThrow();
        });
        assertThat(unit.getUnitStatus()).isEqualTo(status);
        assertThat(unit.getWarehouseId()).isEqualTo(warehouseId);
        assertThat(unit.getStorageLocationId()).isEqualTo(locationId);
    }

    private void assertLifecycleRows(
        UUID unitId,
        MaterialUnitOperationType operationType,
        MaterialUnitEventType eventType,
        long expected
    ) {
        setContext();
        assertThat(operations.findAll().stream()
            .filter(operation -> unitId.equals(operation.getMaterialUnitId()))
            .filter(operation -> operationType == operation.getOperationType())
            .count()).isEqualTo(expected);
        assertThat(events.findAll().stream()
            .filter(event -> unitId.equals(event.getMaterialUnitId()))
            .filter(event -> eventType == event.getEventType())
            .count()).isEqualTo(expected);
    }

    private List<Throwable> race(int attempts, Callable<Void> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = java.util.stream.IntStream.range(0, attempts)
                .mapToObj(ignored -> executor.submit(() -> {
                    start.await();
                    try {
                        action.call();
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                }))
                .toList();
            start.countDown();
            return futures.stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception ex) {
                        return ex;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> callback) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                setContext();
                return callback.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private void setContext() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
    }
}

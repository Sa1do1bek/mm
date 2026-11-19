package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.payload.LaboratoryTestCreateRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.QualityInspectionRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.QualityInspectionService;
import com.enterprise.mm.service.LaboratoryTestService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
class QualityInspectionLockingTest {
    @Autowired private QualityInspectionRepository inspectionRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private WarehouseRepository warehouseRepository;
    @Autowired private QualityInspectionService inspectionService;
    @Autowired private LaboratoryTestService laboratoryTestService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private String tenantId;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        tenantId = "quality-lock-" + UUID.randomUUID();
        companyId = UUID.randomUUID();
        setContext();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void repositoryDeclaresTenantCompanyScopedPessimisticParentLock() {
        Method method = Arrays.stream(QualityInspectionRepository.class.getMethods())
            .filter(candidate -> candidate.getName().equals("findForUpdate"))
            .findFirst()
            .orElse(null);

        assertThat(method).as("quality inspection parent lock lookup").isNotNull();
        assertThat(method.getParameterTypes())
            .containsExactly(String.class, UUID.class, UUID.class);
        assertThat(method.getAnnotation(Lock.class))
            .isNotNull()
            .extracting(Lock::value)
            .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(method.getAnnotation(Query.class).value())
            .contains("tenantId", "companyId", "isDeleted");
    }

    @Test
    void reconciliationWaitsForTheSharedInspectionParentLock() throws Exception {
        QualityInspection inspection = inspection();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch reconcileReady = new CountDownLatch(1);
        CountDownLatch startReconcile = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> withContext(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    QualityInspection locked = entityManager.find(
                        QualityInspection.class,
                        inspection.getId(),
                        LockModeType.PESSIMISTIC_WRITE
                    );
                    assertThat(locked).isNotNull();
                    lockAcquired.countDown();
                    await(releaseLock);
                })
            ));
            assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> reconciliation = executor.submit(() -> withContext(() -> {
                reconcileReady.countDown();
                await(startReconcile);
                inspectionService.reconcileLaboratoryStatus(inspection.getId(), null);
            }));
            assertThat(reconcileReady.await(10, TimeUnit.SECONDS)).isTrue();
            startReconcile.countDown();

            try {
                assertThatThrownBy(() -> reconciliation.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            } finally {
                releaseLock.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            reconciliation.get(10, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = QualityInspectionStatus.class,
        names = {"PASSED", "FAILED", "RELEASED", "REJECTED", "CANCELLED"}
    )
    void laboratoryTestCreationRejectsClosedInspectionStates(QualityInspectionStatus status) {
        QualityInspection inspection = inspection(status);
        LaboratoryTestCreateRequest request = new LaboratoryTestCreateRequest();
        request.setName("Forbidden post-decision laboratory test");
        request.setQualityInspectionId(inspection.getId());
        request.setTestType("CHEMICAL_COMPOSITION");

        assertThatThrownBy(() -> laboratoryTestService.create(request))
            .hasMessageContaining(
                "cannot be created when quality inspection status is " + status.name());
    }

    private QualityInspection inspection() {
        return inspection(QualityInspectionStatus.LAB_TESTING);
    }

    private QualityInspection inspection(QualityInspectionStatus status) {
        Material material = new Material();
        material.setCode("MAT-LOCK-" + suffix());
        material.setName(material.getCode());
        material.setStatus("ACTIVE");
        material = materialRepository.saveAndFlush(material);

        Warehouse warehouse = new Warehouse();
        warehouse.setCode("WH-LOCK-" + suffix());
        warehouse.setName(warehouse.getCode());
        warehouse.setStatus("ACTIVE");
        warehouse = warehouseRepository.saveAndFlush(warehouse);

        QualityInspection inspection = new QualityInspection();
        inspection.setCode("QI-LOCK-" + suffix());
        inspection.setName(inspection.getCode());
        inspection.setStatus(status.name());
        inspection.setInspectionNumber(inspection.getCode());
        inspection.setInspectionType("INCOMING");
        inspection.setMaterialId(material.getId());
        inspection.setWarehouseId(warehouse.getId());
        inspection.setReceivedQuantity(BigDecimal.ONE);
        inspection.setQuarantinedQuantity(BigDecimal.ONE);
        return inspectionRepository.saveAndFlush(inspection);
    }

    private void withContext(Runnable action) {
        setContext();
        try {
            action.run();
        } finally {
            TenantContext.clear();
            CompanyContext.clear();
        }
    }

    private void setContext() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for quality lock test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

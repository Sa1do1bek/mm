package com.enterprise.mm.quality.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueFilter;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueKey;
import com.enterprise.mm.quality.workspace.repository.IncomingQualityWorkspaceQueryRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class IncomingQualityWorkspaceQueryRepositoryTest {
    private static final String TENANT = "tenant-incoming-quality-queue";

    @Autowired private IncomingQualityWorkspaceQueryRepository repository;
    @Autowired private EntityManager entityManager;

    private UUID companyId;
    private Supplier supplier;
    private Material material;
    private Warehouse warehouse;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        supplier = persist(base(new Supplier(), "SUP-IQ", "Incoming supplier", companyId));
        supplier.setLegalName("Incoming Quality Supplier");
        material = persist(base(new Material(), "MAT-IQ", "Incoming material", companyId));
        warehouse = persist(base(new Warehouse(), "WH-IQ", "Incoming warehouse", companyId));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void openQueueContainsExactlyNonTerminalWorkflowStatuses() {
        for (QualityInspectionStatus status : QualityInspectionStatus.values()) {
            inspection("IQ-OPEN-" + status, status, LocalDate.now().plusDays(1));
        }
        entityManager.flush();

        var page = repository.search(
            new QueueFilter(QueueKey.OPEN, null, null, null),
            PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).extracting(QualityInspection::getStatus)
            .containsExactlyInAnyOrder(
                QualityInspectionStatus.DRAFT.name(),
                QualityInspectionStatus.IN_QUARANTINE.name(),
                QualityInspectionStatus.SAMPLING.name(),
                QualityInspectionStatus.LAB_TESTING.name(),
                QualityInspectionStatus.FAILED.name(),
                QualityInspectionStatus.PASSED.name()
            );
    }

    @Test
    void overdueQueueRequiresOpenStatusAndDueDateStrictlyBeforeToday() {
        inspection("IQ-OVERDUE-DRAFT", QualityInspectionStatus.DRAFT, LocalDate.now().minusDays(1));
        inspection("IQ-OVERDUE-FAILED", QualityInspectionStatus.FAILED, LocalDate.now().minusDays(2));
        inspection("IQ-DUE-TODAY", QualityInspectionStatus.DRAFT, LocalDate.now());
        inspection("IQ-DUE-FUTURE", QualityInspectionStatus.DRAFT, LocalDate.now().plusDays(1));
        inspection("IQ-DUE-NULL", QualityInspectionStatus.DRAFT, null);
        inspection("IQ-TERMINAL-PAST", QualityInspectionStatus.RELEASED, LocalDate.now().minusDays(3));
        entityManager.flush();

        var page = repository.search(
            new QueueFilter(QueueKey.OVERDUE, null, null, null),
            PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).extracting(QualityInspection::getInspectionNumber)
            .containsExactly("IQ-OVERDUE-FAILED", "IQ-OVERDUE-DRAFT");
    }

    @Test
    void completedQueueContainsExactlyTerminalStatuses() {
        for (QualityInspectionStatus status : QualityInspectionStatus.values()) {
            inspection("IQ-COMPLETED-" + status, status, LocalDate.now());
        }
        entityManager.flush();

        var page = repository.search(
            new QueueFilter(QueueKey.COMPLETED, null, null, null),
            PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).extracting(QualityInspection::getStatus)
            .containsExactlyInAnyOrder(
                QualityInspectionStatus.RELEASED.name(),
                QualityInspectionStatus.REJECTED.name(),
                QualityInspectionStatus.CANCELLED.name()
            );
    }

    @Test
    void searchIsTrimmedCaseInsensitiveAndMatchesInspectionIdentity() {
        inspection("IQ-SEARCH-NEEDLE", QualityInspectionStatus.DRAFT, LocalDate.now().plusDays(1));
        inspection("IQ-SEARCH-OTHER", QualityInspectionStatus.DRAFT, LocalDate.now().plusDays(1));
        entityManager.flush();

        var page = repository.search(
            new QueueFilter(QueueKey.OPEN, "  search-NEEDLE  ", null, null),
            PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).extracting(QualityInspection::getInspectionNumber)
            .containsExactly("IQ-SEARCH-NEEDLE");
    }

    @Test
    void warehouseAndSupplierFiltersAreAppliedTogether() {
        Supplier otherSupplier = persist(base(new Supplier(), "SUP-IQ-OTHER", "Other supplier", companyId));
        otherSupplier.setLegalName("Other supplier");
        Warehouse otherWarehouse = persist(base(new Warehouse(), "WH-IQ-OTHER", "Other warehouse", companyId));
        inspection("IQ-FILTER-MATCH", QualityInspectionStatus.DRAFT, LocalDate.now(), supplier.getId(), warehouse.getId());
        inspection("IQ-FILTER-WAREHOUSE", QualityInspectionStatus.DRAFT, LocalDate.now(), supplier.getId(), otherWarehouse.getId());
        inspection("IQ-FILTER-SUPPLIER", QualityInspectionStatus.DRAFT, LocalDate.now(), otherSupplier.getId(), warehouse.getId());
        entityManager.flush();

        var page = repository.search(
            new QueueFilter(QueueKey.OPEN, null, warehouse.getId(), supplier.getId()),
            PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).extracting(QualityInspection::getInspectionNumber)
            .containsExactly("IQ-FILTER-MATCH");
    }

    @Test
    void predicateAlwaysAppliesTenantCompanyDeletedAndIncomingScope() {
        inspection("IQ-SCOPE-MATCH", QualityInspectionStatus.DRAFT, LocalDate.now());
        QualityInspection deleted = inspection("IQ-SCOPE-DELETED", QualityInspectionStatus.DRAFT, LocalDate.now());
        deleted.softDelete();
        QualityInspection otherType = inspection("IQ-SCOPE-PROCESS", QualityInspectionStatus.DRAFT, LocalDate.now());
        otherType.setInspectionType("PROCESS");
        inspection(
            "IQ-SCOPE-COMPANY",
            QualityInspectionStatus.DRAFT,
            LocalDate.now(),
            supplier.getId(),
            warehouse.getId(),
            UUID.randomUUID(),
            TENANT
        );
        inspection(
            "IQ-SCOPE-TENANT",
            QualityInspectionStatus.DRAFT,
            LocalDate.now(),
            supplier.getId(),
            warehouse.getId(),
            companyId,
            "tenant-incoming-quality-other"
        );
        entityManager.flush();

        var page = repository.search(
            new QueueFilter(QueueKey.OPEN, "IQ-SCOPE", null, null),
            PageRequest.of(0, 20)
        );

        assertThat(page.getContent()).extracting(QualityInspection::getInspectionNumber)
            .containsExactly("IQ-SCOPE-MATCH");
    }

    @Test
    void countAndSearchShareTheSamePredicateAndReturnStableBusinessOrdering() {
        LocalDateTime baseTime = LocalDateTime.now().minusDays(10);
        inspection("IQ-PARITY-NULL-OLD", QualityInspectionStatus.DRAFT, null)
            .setCreatedAt(baseTime.plusHours(1));
        inspection("IQ-PARITY-FUTURE", QualityInspectionStatus.DRAFT, LocalDate.now().plusDays(2))
            .setCreatedAt(baseTime.plusHours(2));
        inspection("IQ-PARITY-OVERDUE-LATER", QualityInspectionStatus.DRAFT, LocalDate.now().minusDays(1))
            .setCreatedAt(baseTime.plusHours(3));
        inspection("IQ-PARITY-OVERDUE-EARLIER", QualityInspectionStatus.SAMPLING, LocalDate.now().minusDays(3))
            .setCreatedAt(baseTime.plusHours(4));
        inspection("IQ-PARITY-NULL-NEW", QualityInspectionStatus.FAILED, null)
            .setCreatedAt(baseTime.plusHours(5));
        inspection("IQ-NONMATCH", QualityInspectionStatus.DRAFT, LocalDate.now().minusDays(5));
        entityManager.flush();

        QueueFilter filter = new QueueFilter(
            QueueKey.OPEN, "IQ-PARITY", warehouse.getId(), supplier.getId());
        var page = repository.search(filter, PageRequest.of(0, 20));

        assertThat(repository.count(filter)).isEqualTo(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).extracting(QualityInspection::getInspectionNumber)
            .containsExactly(
                "IQ-PARITY-OVERDUE-EARLIER",
                "IQ-PARITY-OVERDUE-LATER",
                "IQ-PARITY-FUTURE",
                "IQ-PARITY-NULL-NEW",
                "IQ-PARITY-NULL-OLD"
            );
    }

    private QualityInspection inspection(
        String number,
        QualityInspectionStatus status,
        LocalDate dueDate
    ) {
        return inspection(number, status, dueDate, supplier.getId(), warehouse.getId());
    }

    private QualityInspection inspection(
        String number,
        QualityInspectionStatus status,
        LocalDate dueDate,
        UUID supplierId,
        UUID warehouseId
    ) {
        return inspection(number, status, dueDate, supplierId, warehouseId, companyId, TENANT);
    }

    private QualityInspection inspection(
        String number,
        QualityInspectionStatus status,
        LocalDate dueDate,
        UUID supplierId,
        UUID warehouseId,
        UUID inspectionCompanyId,
        String tenantId
    ) {
        QualityInspection inspection = base(
            new QualityInspection(), number, "Inspection " + number, inspectionCompanyId);
        inspection.setTenantId(tenantId);
        inspection.setInspectionNumber(number);
        inspection.setInspectionType("INCOMING");
        inspection.setStatus(status.name());
        inspection.setSupplierId(supplierId);
        inspection.setMaterialId(material.getId());
        inspection.setWarehouseId(warehouseId);
        inspection.setReceivedQuantity(BigDecimal.TEN);
        inspection.setDueDate(dueDate);
        entityManager.persist(inspection);
        return inspection;
    }

    private <T extends BusinessEntity> T base(
        T entity,
        String code,
        String name,
        UUID entityCompanyId
    ) {
        entity.setTenantId(TENANT);
        entity.setCompanyId(entityCompanyId);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}

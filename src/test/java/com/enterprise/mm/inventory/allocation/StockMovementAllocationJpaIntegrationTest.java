package com.enterprise.mm.inventory.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementAllocation;
import com.enterprise.mm.entity.StockMovementAllocationCostLayer;
import com.enterprise.mm.repository.StockMovementAllocationCostLayerRepository;
import com.enterprise.mm.repository.StockMovementAllocationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

@SpringJUnitConfig(StockMovementAllocationJpaIntegrationTest.Config.class)
class StockMovementAllocationJpaIntegrationTest {
    private final StockMovementAllocationWriter writer;
    private final StockMovementAllocationRepository allocationRepository;
    private final StockMovementAllocationCostLayerRepository lineageRepository;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    @Autowired
    StockMovementAllocationJpaIntegrationTest(
        StockMovementAllocationWriter writer,
        StockMovementAllocationRepository allocationRepository,
        StockMovementAllocationCostLayerRepository lineageRepository,
        PlatformTransactionManager transactionManager,
        EntityManager entityManager
    ) {
        this.writer = writer;
        this.allocationRepository = allocationRepository;
        this.lineageRepository = lineageRepository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.entityManager = entityManager;
    }

    @Test
    void proxyRequiresTransactionAndRealHibernatePersistsOrderedScopedEvidence() {
        StockMovement primary = movement("tenant-a", company(1), movementId(), material(1));

        assertThatThrownBy(() -> writer.write(primary, evidence(primary, 2)))
            .isInstanceOf(IllegalTransactionStateException.class);

        transactions.executeWithoutResult(status -> {
            writer.write(primary, evidence(primary, 2));
            writer.write(primary, evidence(primary, 1));
            writer.write(movement("tenant-b", primary.getCompanyId(), primary.getId(), primary.getMaterialId()),
                evidence(movement("tenant-b", primary.getCompanyId(), primary.getId(), primary.getMaterialId()), 1));
            writer.write(movement(primary.getTenantId(), company(2), primary.getId(), primary.getMaterialId()),
                evidence(movement(primary.getTenantId(), company(2), primary.getId(), primary.getMaterialId()), 1));

            List<StockMovementAllocation> current = allocationRepository
                .findByTenantIdAndCompanyIdAndMovementId(primary.getTenantId(), primary.getCompanyId(), primary.getId());
            StockMovementAllocation first = current.get(0);
            StockMovementAllocationCostLayer duplicate = duplicateLineage(first);
            entityManager.persist(duplicate);
            entityManager.flush();
        });

        transactions.executeWithoutResult(status -> {
            List<StockMovementAllocation> allocations = allocationRepository
                .findByTenantIdAndCompanyIdAndMovementId(primary.getTenantId(), primary.getCompanyId(), primary.getId());
            assertThat(allocations).hasSize(3);
            assertThat(allocations).allSatisfy(row -> {
                assertThat(row.getId()).isNotNull();
                assertThat(row.getTenantId()).isEqualTo(primary.getTenantId());
                assertThat(row.getCompanyId()).isEqualTo(primary.getCompanyId());
            });
            assertThat(orderKeys(allocations)).isSorted();

            List<StockMovementAllocationCostLayer> lineages = lineageRepository
                .findByTenantIdAndCompanyIdAndMovementId(primary.getTenantId(), primary.getCompanyId(), primary.getId());
            assertThat(lineages).hasSize(4);
            assertThat(lineages).allSatisfy(row -> {
                assertThat(row.getId()).isNotNull();
                assertThat(row.getTenantId()).isEqualTo(primary.getTenantId());
                assertThat(row.getCompanyId()).isEqualTo(primary.getCompanyId());
                assertThat(allocations).extracting(StockMovementAllocation::getId)
                    .contains(row.getAllocationId());
            });
            assertThat(lineageOrderKeys(lineages, allocations)).isSorted();

            assertThat(allocationRepository.findByTenantIdAndCompanyIdAndMovementId(
                "tenant-b", primary.getCompanyId(), primary.getId())).hasSize(1);
            assertThat(allocationRepository.findByTenantIdAndCompanyIdAndMovementId(
                primary.getTenantId(), company(2), primary.getId())).hasSize(1);
            assertThat(lineageRepository.findByTenantIdAndCompanyIdAndMovementId(
                "missing-tenant", primary.getCompanyId(), primary.getId())).isEmpty();
        });
    }

    private List<String> orderKeys(List<StockMovementAllocation> allocations) {
        return allocations.stream()
            .map(row -> "%08d/%s".formatted(row.getAllocationSequence(), row.getId()))
            .toList();
    }

    private List<String> lineageOrderKeys(
        List<StockMovementAllocationCostLayer> lineages,
        List<StockMovementAllocation> allocations
    ) {
        Map<UUID, StockMovementAllocation> byId = allocations.stream().collect(
            java.util.stream.Collectors.toMap(StockMovementAllocation::getId, row -> row));
        return lineages.stream().map(row -> {
            StockMovementAllocation allocation = byId.get(row.getAllocationId());
            return "%08d/%s/%08d/%s".formatted(
                allocation.getAllocationSequence(), allocation.getId(), row.getLineageSequence(), row.getId());
        }).toList();
    }

    private StockMovementAllocationCostLayer duplicateLineage(StockMovementAllocation allocation) {
        StockMovementAllocationCostLayer row = new StockMovementAllocationCostLayer();
        row.setTenantId(allocation.getTenantId());
        row.setCompanyId(allocation.getCompanyId());
        row.setCreatedAt(allocation.getCreatedAt());
        row.setCreatedBy("integration-test");
        row.setAllocationId(allocation.getId());
        row.setLineageSequence(1);
        row.setLineageRole("MANUAL");
        row.setQuantityDelta(BigDecimal.ONE);
        row.setValueDelta(BigDecimal.ONE);
        return row;
    }

    private StockMovementAllocationEvidence evidence(StockMovement movement, int allocationCount) {
        List<StockMovementAllocationDraft> allocations = new ArrayList<>();
        for (int index = 0; index < allocationCount; index++) {
            allocations.add(new StockMovementAllocationDraft(
                index == 0 ? "SOURCE" : "TARGET", null,
                StockMovementStockKeySnapshot.empty(), StockMovementStockKeySnapshot.empty(),
                null, "PCS", index == 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE,
                index == 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO,
                index == 0 ? BigDecimal.TEN.negate() : BigDecimal.TEN,
                index == 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE,
                BigDecimal.TEN, "UZS", BigDecimal.ONE,
                List.of(new StockMovementCostLineageDraft(
                    index == 0 ? "CONSUMED" : "CREATED", null, null, null,
                    index == 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE,
                    index == 0 ? BigDecimal.TEN.negate() : BigDecimal.TEN,
                    BigDecimal.TEN, "UZS", BigDecimal.ONE))));
        }
        return new StockMovementAllocationEvidence(
            movement.getTenantId(), movement.getCompanyId(), movement.getId(), movement.getMaterialId(),
            movement.getSourceInventoryItemId(), allocations);
    }

    private StockMovement movement(String tenant, UUID company, UUID id, UUID material) {
        StockMovement movement = new StockMovement();
        movement.setTenantId(tenant);
        movement.setCompanyId(company);
        movement.setId(id);
        movement.setMaterialId(material);
        return movement;
    }

    private UUID movementId() {
        return UUID.fromString("70000000-0000-0000-0000-000000000001");
    }

    private UUID company(int suffix) {
        return UUID.fromString("10000000-0000-0000-0000-%012d".formatted(suffix));
    }

    private UUID material(int suffix) {
        return UUID.fromString("20000000-0000-0000-0000-%012d".formatted(suffix));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                "jdbc:h2:mem:allocation-writer;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "sa",
                ""
            );
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setManagedTypes(PersistenceManagedTypes.of(
                StockMovementAllocation.class.getName(),
                StockMovementAllocationCostLayer.class.getName()
            ));
            factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect",
                "hibernate.show_sql", "false"
            ));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        EntityManager entityManager(EntityManagerFactory entityManagerFactory) {
            return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        }

        @Bean
        StockMovementAllocationRepository allocationRepository(EntityManager entityManager) {
            return new JpaRepositoryFactory(entityManager).getRepository(StockMovementAllocationRepository.class);
        }

        @Bean
        StockMovementAllocationCostLayerRepository lineageRepository(EntityManager entityManager) {
            return new JpaRepositoryFactory(entityManager)
                .getRepository(StockMovementAllocationCostLayerRepository.class);
        }

        @Bean
        StockMovementAllocationReferenceVerifier referenceVerifier() {
            return mock(StockMovementAllocationReferenceVerifier.class);
        }

        @Bean
        StockMovementAllocationWriter writer(
            EntityManager entityManager,
            StockMovementAllocationReferenceVerifier referenceVerifier
        ) {
            return new StockMovementAllocationWriter(entityManager, referenceVerifier);
        }
    }
}

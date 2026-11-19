package com.enterprise.mm.warehouse.transfergroup;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs on H2 by default and on an isolated PostgreSQL with TRANSFER_GROUP_TEST_POSTGRES_URL. */
@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TransferGroupOptionQueryTest.Config.class)
class TransferGroupOptionQueryTest {
    @Configuration
    @TestComponent
    @EntityScan(basePackageClasses = WarehouseTransferGroup.class)
    @EnableJpaRepositories(basePackageClasses = WarehouseTransferGroupRepository.class)
    static class Config {}

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("TRANSFER_GROUP_TEST_POSTGRES_URL");
        if (url != null) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> "transfer_test");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }
    }

    @Autowired WarehouseTransferGroupRepository repository;
    @Autowired TestEntityManager em;
    String tenant = "option-query";
    UUID company = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    UUID otherSource = UUID.randomUUID();

    @BeforeEach void setup() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
    }
    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void filtersTenantCompanyAndSoftDeleteBeforeCountingButKeepsCompletedGroups() {
        var active = group("WTG-A", "Reagents", source, "ACTIVE");
        var closed = group("WTG-C", "Completed distribution", source, "CLOSED");
        var otherTenant = group("WTG-T", "Other tenant", source, "ACTIVE");
        otherTenant.setTenantId("another-tenant");
        var otherCompany = group("WTG-X", "Other company", source, "ACTIVE");
        otherCompany.setCompanyId(UUID.randomUUID());
        var deleted = group("WTG-D", "Deleted", source, "ACTIVE");
        deleted.softDelete();
        em.flush(); em.clear();

        var result = options(null, null, null, 0, 1);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(options(null, null, null, 0, 20).getContent())
            .extracting(TransferGroupOptionResponse::id).containsExactlyInAnyOrder(active.getId(), closed.getId());
    }

    @Test void searchesCodeOrAnyStoredNameTranslationAndNotDescription() {
        var byCode = group("WTG-REAGENT", "Other supplies", source, "ACTIVE");
        var byName = group("WTG-NAME", "ru: Реактивы\nen: Reagents\nuz: Reagentlar", source, "ACTIVE");
        var descriptionOnly = group("WTG-DESC", "Pipes", source, "ACTIVE");
        descriptionOnly.setDescription("reagent");
        em.flush(); em.clear();
        assertThat(options(null, null, "%reagent%", 0, 20).getContent())
            .extracting(TransferGroupOptionResponse::id).containsExactlyInAnyOrder(byCode.getId(), byName.getId());
        assertThat(options(null, null, "%реактив%", 0, 20).getContent())
            .extracting(TransferGroupOptionResponse::id).containsExactly(byName.getId());
    }

    @Test void wildcardAndEscapeCharactersAreLiteralInCodeAndName() {
        var literal = group("WTG-50%_!", "Concentrate", source, "ACTIVE");
        group("WTG-50XX", "Concentrate", source, "ACTIVE");
        var nameLiteral = group("WTG-N", "A_50% liquid!", source, "ACTIVE");
        group("WTG-O", "AB50XX liquidX", source, "ACTIVE");
        em.flush(); em.clear();
        assertThat(options(null, null, "%50!%!_!!%", 0, 20).getContent())
            .extracting(TransferGroupOptionResponse::id).containsExactly(literal.getId());
        assertThat(options(null, null, "%a!_50!% liquid!!%", 0, 20).getContent())
            .extracting(TransferGroupOptionResponse::id).containsExactly(nameLiteral.getId());
    }

    @Test void sourceScopeIsAnIntersectionWithRequestedFilterAndIgnoresDestinationMembership() {
        var own = group("WTG-OWN", "Source supplies", source, "ACTIVE");
        var incoming = group("WTG-IN", "Incoming supplies", otherSource, "ACTIVE");
        var item = new WarehouseTransferGroupItem();
        item.setGroupId(incoming.getId()); item.setDestinationWarehouseId(source);
        item.setMaterialId(UUID.randomUUID()); item.setCreatedAt(LocalDateTime.now()); item.setName("Pipe");
        em.persist(item);
        em.flush(); em.clear();
        assertThat(options(source, null, null, 0, 20).getContent())
            .extracting(TransferGroupOptionResponse::id).containsExactly(own.getId());
        assertThat(options(source, otherSource, null, 0, 20).getTotalElements()).isZero();
        assertThat(options(null, otherSource, null, 0, 20).getContent())
            .extracting(TransferGroupOptionResponse::id).containsExactly(incoming.getId());
    }

    @Test void paginationHasDeterministicIdTieBreakerAndOutOfRangeKeepsCount() {
        for (int i = 0; i < 5; i++) group("WTG-" + i, "Supplies", source, "ACTIVE");
        em.flush(); em.clear();
        var all = options(null, null, null, 0, 20).getContent();
        var first = options(null, null, null, 0, 2);
        var second = options(null, null, null, 1, 2);
        assertThat(first.getContent()).containsExactlyElementsOf(all.subList(0, 2));
        assertThat(second.getContent()).containsExactlyElementsOf(all.subList(2, 4));
        assertThat(first.getContent()).doesNotContainAnyElementsOf(second.getContent());
        var beyond = options(null, null, null, 8, 2);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(5);
        assertThat(beyond.getTotalPages()).isEqualTo(3);
        var empty = options(null, null, "%does-not-exist%", 0, 20);
        assertThat(empty.getContent()).isEmpty();
        assertThat(empty.getTotalElements()).isZero();
        assertThat(empty.getTotalPages()).isZero();
    }

    @Test void listUsesProjectionWithoutHydratingEntitiesOrReadingChildTables() {
        for (int i = 0; i < 3; i++) group("WTG-" + i, "Supplies", source, "ACTIVE");
        em.flush(); em.clear();
        var statistics = em.getEntityManager().getEntityManagerFactory()
            .unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(options(null, null, null, 0, 2).getTotalElements()).isEqualTo(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getCollectionLoadCount()).isZero();
    }

    private org.springframework.data.domain.Page<TransferGroupOptionResponse> options(
        UUID readSource, UUID filterSource, String pattern, int page, int size
    ) {
        return repository.findOptions(tenant, company, readSource, filterSource, pattern,
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    private WarehouseTransferGroup group(String code, String name, UUID warehouse, String status) {
        var group = new WarehouseTransferGroup();
        group.setCode(code); group.setName(name); group.setStatus(status); group.setSourceWarehouseId(warehouse);
        group.setCreatedAt(LocalDateTime.of(2026, 10, 20, 12, 0));
        return em.persist(group);
    }
}

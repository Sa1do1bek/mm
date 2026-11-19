package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.service.MxikReconciliationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.firebase.enabled=false",
    "mm.integrations.cbu.enabled=false"
})
@ActiveProfiles("test")
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers
@Transactional
class MxikReconciliationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired MaterialRepository materials;
    @Autowired AiAnalysisJobRepository jobs;
    @Autowired MxikReconciliationService reconciliation;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("mxik-query-test");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void listsMissingMxikWithAndWithoutSearch() {
        Material material = new Material();
        material.setCode("PUMP-001");
        material.setName("Pump");
        material.setStatus("ACTIVE");
        materials.saveAndFlush(material);

        var page = PaginationUtils.pageRequest(0, 25, Sort.by("name").ascending());
        assertThat(reconciliation.list(true, null, page).getContent())
            .extracting(MxikReconciliationService.MaterialRow::code)
            .contains("PUMP-001");
        assertThat(reconciliation.list(true, "pump", page).getContent())
            .extracting(MxikReconciliationService.MaterialRow::code)
            .contains("PUMP-001");
    }

    @Test
    void keepsLegacyMaterialVisibleWhenItsMxikIsAbsentFromCatalog() {
        Material material = new Material();
        material.setCode("LEGACY-001");
        material.setName("Legacy pump");
        material.setStatus("ACTIVE");
        material.setMxikCode("00104001001000000");
        var saved = materials.saveAndFlush(material);
        entityManager.clear();

        var page = PaginationUtils.pageRequest(0, 25, Sort.by("name").ascending());
        var all = reconciliation.list(false, null, page).getContent();
        assertThat(all).extracting(MxikReconciliationService.MaterialRow::code)
            .contains("LEGACY-001");
        var row = all.stream().filter(item -> item.id().equals(saved.getId())).findFirst().orElseThrow();
        assertThat(row.mxikCode()).isEqualTo("00104001001000000");
        assertThat(row.mxikName()).isNull();
        assertThat(reconciliation.list(false, "legacy", page).getContent())
            .extracting(MxikReconciliationService.MaterialRow::code)
            .contains("LEGACY-001");
        assertThat(reconciliation.get(saved.getId()).mxikName()).isNull();
    }

    @Test
    void showsOnlyCurrentMxikRecommendationOnMaterialRow() {
        Material material = new Material();
        material.setCode("RECOMMEND-001");
        material.setName("Recommendation test material");
        material.setStatus("ACTIVE");
        material = materials.saveAndFlush(material);
        AiAnalysisJob job = AiAnalysisJob.queued(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            AiAnalysisType.MATERIAL_MXIK_MATCH, "MATERIAL", material.getId(), "hash", "uz", "tester",
            "{\"materialId\":\"" + material.getId() + "\"}");
        job.setStatus(AiAnalysisJobStatus.COMPLETED);
        job.setResultPayload("{\"materialId\":\"" + material.getId() + "\",\"materialVersion\":"
            + material.getVersion() + ",\"status\":\"RECOMMENDED\",\"recommendedMxik\":{\"code\":\"123456789\"},\"confidence\":\"MEDIUM\"}");
        job = jobs.saveAndFlush(job);
        entityManager.clear();

        var row = reconciliation.get(material.getId());
        assertThat(row.suggestedMxikCode()).isEqualTo("123456789");
        assertThat(row.suggestionJobId()).isEqualTo(job.getId());
        assertThat(row.suggestionConfidence()).isEqualTo("MEDIUM");
    }
}

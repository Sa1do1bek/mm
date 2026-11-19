package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.repository.ChartOfAccountRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

class MaterialMatchingPostgresTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate named;

    private final String tenant = "material-matching-test";
    private final UUID company = UUID.randomUUID();
    private final UUID materialId = UUID.randomUUID();
    private final LocalDate snapshotDate = LocalDate.of(2026, 11, 10);

    @BeforeAll
    static void migrate() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        POSTGRES.start();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("13")).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("alter table materials add column manual_receipt_stock_account_id uuid");
        named = new NamedParameterJdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void latestCompletedBalanceAccountsDriveMatchingAndReceiptResolution() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        jdbc.update("""
            insert into materials(id,tenant_id,company_id,created_at,code,name,status,
              material_type,tracking_mode,batch_tracking_policy,serial_tracking_policy)
            values (?,?,?,current_timestamp,'MAT-1','Material','ACTIVE','TMB','QUANTITY','NONE','NONE')
            """, materialId, tenant, company);
        UUID firstAccount = addAccount("1010");
        UUID secondAccount = addAccount("1030");
        UUID importId = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_account_imports(id,tenant_id,company_id,organization_source_id,
              snapshot_date,manifest_sha256,expected_counts,expected_records,accepted_records,
              expected_chain,chain_sha256,status,completed_at)
            values (?,?,?,?,?,?,'{}'::jsonb,0,0,?,?,'COMPLETED',current_timestamp)
            """, importId, tenant, company, "organization-1", snapshotDate, "a".repeat(64),
            "b".repeat(64), "b".repeat(64));
        addBalance(importId, 1, "1010");

        var jobs = mock(AiAnalysisJobRepository.class);
        when(jobs.findForMaterials(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection()))
            .thenReturn(java.util.List.of());
        var matching = new MaterialMatchingQuery(named, jobs, new ObjectMapper());
        assertThat(matching.list("ALL", null, null, null, 0, 25).content())
            .singleElement().satisfies(row -> {
                assertThat(row.accountStatus()).isEqualTo("AUTO_1C");
                assertThat(row.effectiveAccount().id()).isEqualTo(firstAccount);
            });
        var job = mock(AiAnalysisJob.class);
        UUID jobId = UUID.randomUUID();
        long version = matching.list("ALL", null, null, null, 0, 25).content().getFirst().version();
        when(job.getEntityId()).thenReturn(materialId);
        when(job.getId()).thenReturn(jobId);
        when(job.getStatus()).thenReturn(AiAnalysisJobStatus.COMPLETED);
        when(job.getResultPayload()).thenReturn("""
            {"status":"RECOMMENDED","materialId":"%s","materialVersion":%d,
             "recommendedMxik":{"code":"123456"},"confidence":"MEDIUM","reason":"Matched"}
            """.formatted(materialId, version));
        when(jobs.findForMaterials(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection()))
            .thenReturn(java.util.List.of(job));
        assertThat(matching.list("ALL", null, null, null, 0, 25).content())
            .singleElement().satisfies(row -> {
                assertThat(row.suggestedMxikCode()).isEqualTo("123456");
                assertThat(row.suggestionJobId()).isEqualTo(jobId);
            });
        Material material = new Material();
        material.setId(materialId);
        material.setTenantId(tenant);
        material.setCompanyId(company);
        material.setStatus("ACTIVE");
        var materials = mock(MaterialRepository.class);
        when(materials.findByTenantIdAndIdAndIsDeletedFalse(tenant, materialId))
            .thenReturn(Optional.of(material));
        var resolution = new MaterialStockAccountResolutionService(named, materials,
            mock(StockAccountResolver.class), mock(ChartOfAccountRepository.class),
            mock(RoleDataScopeService.class));
        assertThat(resolution.get(materialId).effectiveAccount().id()).isEqualTo(firstAccount);

        addBalance(importId, 2, "1030");
        assertThat(matching.list("MISSING_STOCK_ACCOUNT", null, null, null, 0, 25).content())
            .singleElement().satisfies(row -> {
                assertThat(row.accountStatus()).isEqualTo("CHOICE_REQUIRED");
                assertThat(row.candidateCount()).isEqualTo(2);
                assertThat(row.effectiveAccount()).isNull();
            });
        var multiple = resolution.get(materialId);
        assertThat(multiple.candidateAccounts()).extracting(MaterialStockAccountResolutionService.Account::id)
            .containsExactlyInAnyOrder(firstAccount, secondAccount);
        assertThat(multiple.effectiveAccount()).isNull();
    }

    private UUID addAccount(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into chart_of_accounts(id,tenant_id,company_id,created_at,code,name,status,
              account_code,is_group)
            values (?,?,?,current_timestamp,?,?,'ACTIVE',?,false)
            """, id, tenant, company, code, code, code);
        return id;
    }

    private void addBalance(UUID importId, int sequence, String accountCode) {
        jdbc.update("""
            insert into warehouse_account_entries(id,tenant_id,company_id,import_id,source_seq,
              source_key,kind,source_date,account_source_id,account_code,account_name,
              material_reference_type,material_source_id,warehouse_source_id,material_id,
              material_code,material_name,warehouse_code,warehouse_name,source_type,
              source_material_type,stock_category,uom,uom_name_ru,quantity,amount,
              additional_dimensions,row_sha256,content)
            values (?,?,?,?,?,?,'BALANCE',?,'account-source',?,'Materials','00000036',
              'material-source','warehouse-source',?,'MAT-1','Material','WH','Warehouse',
              'MATERIAL','RAW','OWNED','PCS','pieces',?,1.0000,'{}'::jsonb,?,
              '{"currencyCode":"UZS"}')
            """, UUID.randomUUID(), tenant, company, importId, sequence, "balance-" + sequence,
            snapshotDate, accountCode, materialId, new BigDecimal("10"), "c".repeat(64));
    }
}

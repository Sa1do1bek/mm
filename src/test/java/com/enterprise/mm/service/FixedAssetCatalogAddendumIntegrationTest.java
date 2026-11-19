package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogChunkRequest;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogRow;
import com.enterprise.mm.payload.MaterialCreateRequest;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.pilot.PilotCapability;
import com.enterprise.mm.security.pilot.PilotCapabilityPolicy;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Catalog-only fixed assets need no instances, serial facts, profiles or book values. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.features.fixed-assets.enabled=true", "mm.pilot.enabled-capabilities="})
class FixedAssetCatalogAddendumIntegrationTest {
    @Autowired CatalogMigrationService catalogs;
    @Autowired MaterialService materialService;
    @Autowired PilotCapabilityPolicy pilot;
    @Autowired EntityManager entities;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    private String tenant;
    private UUID company;
    private UUID group;
    private UUID unit;

    @BeforeEach
    void setup() {
        tenant = "fixed-catalog-" + UUID.randomUUID();
        TenantContext.setTenant(tenant);
        var auth = new UsernamePasswordAuthenticationToken("fixed-catalog.test", null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        auth.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(auth);
        transactions.executeWithoutResult(status -> {
            Company c = base(new Company(), "CATALOG-COMPANY", UUID.randomUUID());
            entities.persist(c); company = c.getId(); c.setCompanyId(company);
            CompanyContext.setCompany(company);
            MaterialGroup g = base(new MaterialGroup(), "CATALOG-GROUP", company);
            entities.persist(g); group = g.getId();
            UnitOfMeasure u = base(new UnitOfMeasure(), "CATALOG-UNIT", company);
            u.setMeasurementNameShort("EA"); u.setMeasurementNameLong("Pieces");
            u.setUnitType(MeasurementUnitType.QUANTITY); entities.persist(u); unit = u.getId();
        });
    }

    @AfterEach
    void cleanup() {
        ReflectionTestUtils.setField(materialService, "fixedAssetsEnabled", true);
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear();
    }

    @Test
    void enabledCatalogToggleCreatesEightTypedRowsWithoutAssetInstancesOrFinancialStock() {
        assertThat(pilot.enabled(PilotCapability.FIXED_ASSET_EXECUTION)).isFalse();
        var request = command();
        var created = catalogs.importMaterials(tenant, company, request);
        assertThat(created.rows()).hasSize(8).allMatch(row -> row.created());
        assertThat(catalogs.importMaterials(tenant, company, request)).isEqualTo(created);
        assertThat(count("materials")).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
            select count(*) from materials where tenant_id=? and company_id=?
                and material_type='FIXED_ASSET' and tracking_mode='QUANTITY'
                and batch_tracking_policy='NONE' and serial_tracking_policy='NONE'
                and average_cost is null
            """, Long.class, tenant, company)).isEqualTo(8);
        assertNoAssetsOrStock();
        for (var row : created.rows()) {
            String description = jdbc.queryForObject("select description from materials where id=?", String.class, row.materialId());
            assertThat(description).contains(CatalogMigrationService.sourceMarker("b".repeat(64), row.sourceId()));
        }
    }

    @Test
    void disabledCatalogToggleRejectsEntireChunkWithoutCreatingAssetOrMaterialData() {
        ReflectionTestUtils.setField(materialService, "fixedAssetsEnabled", false);
        assertThatThrownBy(() -> catalogs.importMaterials(tenant, company, command()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("FIXED_ASSETS_DISABLED");
        assertThat(count("materials")).isZero();
        assertThat(count("idempotency_records")).isZero();
        assertThat(count("audit_logs")).isZero();
        assertNoAssetsOrStock();
    }

    private MaterialCatalogChunkRequest command() {
        List<MaterialCatalogRow> rows = IntStream.rangeClosed(1, 8).mapToObj(i -> {
            MaterialCreateRequest payload = new MaterialCreateRequest();
            payload.setCode("FA-CATALOG-" + i); payload.setName("Source fixed asset catalog " + i);
            payload.setMaterialGroupId(group); payload.setUnitOfMeasureId(unit);
            payload.setMaterialType(MaterialType.FIXED_ASSET); payload.setTrackingMode(TrackingMode.QUANTITY);
            payload.setBatchTrackingPolicy(BatchTrackingPolicy.NONE); payload.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);
            return new MaterialCatalogRow(String.format("%032x", i), payload);
        }).toList();
        return new MaterialCatalogChunkRequest(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "b".repeat(64), rows);
    }

    private void assertNoAssetsOrStock() {
        for (String table : List.of("fixed_assets", "stock_movements", "stock_balances", "stock_batches", "inventory_cost_layers", "stock_cost_movements"))
            assertThat(count(table)).as(table).isZero();
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=? and company_id=?", Long.class, tenant, company);
    }

    private <T extends BusinessEntity> T base(T entity, String code, UUID companyId) {
        entity.setTenantId(tenant); entity.setCompanyId(companyId); entity.setCode(code); entity.setName(code); entity.setStatus("ACTIVE");
        return entity;
    }
}

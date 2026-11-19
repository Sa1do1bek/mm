package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.payload.UnitOfMeasureCreateRequest;
import com.enterprise.mm.security.AccessScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest @ActiveProfiles("test")
class CatalogMasterMigrationServiceIntegrationTest {
    private static final String SOURCE_HASH = "a".repeat(64), PREPARED_HASH = "b".repeat(64);
    private static final String SOURCE_1 = "1".repeat(32), SOURCE_2 = "2".repeat(32);
    @Autowired CatalogMasterMigrationService service;
    @Autowired UnitOfMeasureService unitService;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    private String tenant;
    private UUID company;

    @BeforeEach void setUp() {
        tenant = "master-chunk-" + UUID.randomUUID();
        TenantContext.setTenant(tenant); authenticate("ADMIN");
        transactions.executeWithoutResult(status -> {
            Company entity = base(new Company(), "COMPANY", UUID.randomUUID());
            em.persist(entity); company = entity.getId(); entity.setCompanyId(company);
        });
        CompanyContext.setCompany(company);
    }
    @AfterEach void tearDown() {
        AccessScope.clearRequestedCompanyScope(); SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear();
    }
    @Test void unitChunkAndRepartitionedRowsReplayExactlyWithoutDuplicateMasters() {
        var command = command(unit(SOURCE_1, "First"), unit(SOURCE_2, "Second"));
        var first = service.importMasters("units", tenant, company, command);
        assertThat(first.rows()).hasSize(2).allMatch(MasterRowResponse::created);
        assertThat(service.importMasters("units", tenant, company, command)).isEqualTo(first);
        var repartitioned = new MasterChunkRequest(command.importId(), UUID.randomUUID(), SOURCE_HASH, PREPARED_HASH, command.rows());
        assertThat(service.importMasters("units", tenant, company, repartitioned).rows()).isEqualTo(first.rows());
        assertThat(count("unit_of_measures")).isEqualTo(2);
        for (MasterRowResponse row : first.rows()) {
            String description = jdbc.queryForObject("select description from unit_of_measures where id=?", String.class, row.masterId());
            assertThat(description).contains(PREPARED_HASH + ":Catalog41:" + row.sourceId());
        }
    }
    @Test void categoryAndWarehouseLegacyRowsAdoptOnlyFullMarkerAndExactMetadata() {
        var category = group(SOURCE_1, "CATEGORY-1", "Group one");
        var warehouse = warehouse(SOURCE_2, "Warehouse two");
        UUID categoryId = transactions.execute(status -> {
            MaterialGroup entity = base(new MaterialGroup(), "CATEGORY-1", company);
            entity.setName("Group one"); entity.setDescription(marker(category)); em.persist(entity); return entity.getId();
        });
        UUID warehouseId = transactions.execute(status -> {
            Warehouse entity = base(new Warehouse(), "WAREHOUSE-LEGACY", company);
            entity.setName("Warehouse two"); entity.setDescription(marker(warehouse)); em.persist(entity); return entity.getId();
        });
        var adoptedGroup = service.importMasters("categories", tenant, company, command(category)).rows().getFirst();
        var adoptedWarehouse = service.importMasters("warehouses", tenant, company, command(warehouse)).rows().getFirst();
        assertThat(adoptedGroup.masterId()).isEqualTo(categoryId); assertThat(adoptedGroup.created()).isFalse();
        assertThat(adoptedWarehouse.masterId()).isEqualTo(warehouseId); assertThat(adoptedWarehouse.code()).isEqualTo("WAREHOUSE-LEGACY");
        assertThat(adoptedWarehouse.created()).isFalse(); assertThat(count("audit_logs")).isZero();
    }
    @Test void createsWarehousesWithNormalGeneratedCodesAndReplaysSourceIdentity() {
        var row = warehouse(SOURCE_1, "New warehouse");
        var first = service.importMasters("warehouses", tenant, company, command(row));
        assertThat(first.rows().getFirst().code()).startsWith("WAREHOUSE-");
        var replay = service.importMasters("warehouses", tenant, company, command(row));
        assertThat(replay.rows().getFirst().masterId()).isEqualTo(first.rows().getFirst().masterId());
        assertThat(replay.rows().getFirst().created()).isFalse(); assertThat(count("warehouses")).isEqualTo(1);
    }
    @Test void legacyUnitRequiresFullRecoveryCodeAndAllLanguageLabels() {
        var row = unit(SOURCE_1, "Original");
        UUID id = unitService.create(mapper.convertValue(row.payload(), UnitOfMeasureCreateRequest.class), Locale.ENGLISH).getId();
        var adopted = service.importMasters("units", tenant, company, command(row)).rows().getFirst();
        assertThat(adopted.masterId()).isEqualTo(id); assertThat(adopted.created()).isFalse();
        var changed = unit(SOURCE_1, "Changed");
        assertThatThrownBy(() -> service.importMasters("units", tenant, company, command(changed))).isInstanceOf(ConflictException.class);
        assertThat(count("unit_of_measures")).isEqualTo(1);
    }
    @Test void laterUnitAliasFailureRollsBackMastersAuditsAndIdempotency() {
        var first = unit(SOURCE_1, "Same alias"); var second = unit(SOURCE_2, "Same alias");
        assertThatThrownBy(() -> service.importMasters("units", tenant, company, command(first, second))).isInstanceOf(BadRequestException.class);
        assertThat(count("unit_of_measures")).isZero(); assertThat(count("audit_logs")).isZero();
        assertThat(count("idempotency_records")).isZero(); assertThat(count("integration_events")).isZero();
    }
    @Test void laterCategoryCollisionRollsBackFirstCreateAndItsAudit() {
        transactions.executeWithoutResult(status -> {
            MaterialGroup existing = base(new MaterialGroup(), "CATEGORY-2", company); existing.setName("Existing unrelated"); em.persist(existing);
        });
        assertThatThrownBy(() -> service.importMasters("categories", tenant, company,
            command(group(SOURCE_1, "CATEGORY-1", "First"), group(SOURCE_2, "CATEGORY-2", "Second"))))
            .isInstanceOf(ConflictException.class);
        assertThat(count("material_groups")).isEqualTo(1); assertThat(count("audit_logs")).isZero(); assertThat(count("idempotency_records")).isZero();
    }
    @Test void changedCodeCannotCloneAnAlreadyMarkedSourceIdentity() {
        var row = group(SOURCE_1, "PREPARED-CODE", "Original group");
        transactions.executeWithoutResult(status -> {
            MaterialGroup existing = base(new MaterialGroup(), "RENAMED-CODE", company);
            existing.setName("Original group"); existing.setDescription(marker(row)); em.persist(existing);
        });
        assertThatThrownBy(() -> service.importMasters("categories", tenant, company, command(row))).isInstanceOf(ConflictException.class);
        assertThat(count("material_groups")).isEqualTo(1); assertThat(count("audit_logs")).isZero();
    }
    @Test void importManifestIsBoundAcrossAllMasterKinds() {
        var first = command(unit(SOURCE_1, "Unit")); service.importMasters("units", tenant, company, first);
        var other = new MasterChunkRequest(first.importId(), UUID.randomUUID(), "c".repeat(64), PREPARED_HASH, List.of(group(SOURCE_1, "G-1", "Group")));
        assertThatThrownBy(() -> service.importMasters("categories", tenant, company, other)).isInstanceOf(ConflictException.class);
        assertThat(count("material_groups")).isZero();
    }
    @Test void unknownParentReferencesAndInventedEmployeeAssignmentsRejectBeforeWrites() {
        var group = group(SOURCE_1, "G-1", "Group"); ((ObjectNode) group.payload()).put("parentId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.importMasters("categories", tenant, company, command(group))).isInstanceOf(BadRequestException.class);
        var warehouse = warehouse(SOURCE_1, "Warehouse"); ((ObjectNode) warehouse.payload()).putArray("responsibleEmployeeIds").add(UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.importMasters("warehouses", tenant, company, command(warehouse))).isInstanceOf(BadRequestException.class);
        assertThat(count("material_groups")).isZero(); assertThat(count("warehouses")).isZero(); assertThat(count("idempotency_records")).isZero();
    }
    @Test void foreignTargetIdAndOtherSourceMarkerNeverAdopt() {
        var row = warehouse(SOURCE_1, "Warehouse");
        UUID foreign = transactions.execute(status -> {
            Warehouse entity = base(new Warehouse(), "FOREIGN", UUID.randomUUID()); entity.setName("Warehouse"); entity.setDescription(marker(row)); em.persist(entity); return entity.getId();
        });
        var foreignRow = new MasterRow(row.sourceId(), row.sourceEntityType(), foreign, row.payload());
        assertThatThrownBy(() -> service.importMasters("warehouses", tenant, company, command(foreignRow))).isInstanceOf(ConflictException.class);
        var group = group(SOURCE_1, "G-1", "Group"); ((ObjectNode) group.payload()).put("description", "[1c-mm-migration:" + "c".repeat(64) + ":Catalog6295:" + SOURCE_1 + "]");
        assertThatThrownBy(() -> service.importMasters("categories", tenant, company, command(group))).isInstanceOf(ConflictException.class);
        assertThat(count("warehouses")).isZero(); assertThat(count("material_groups")).isZero();
    }
    @Test void boundedChunksScopeAndRoleChecksApplyBeforeCreates() {
        var row = group(SOURCE_1, "G-1", "Group");
        var tooMany = new MasterChunkRequest(UUID.randomUUID(), UUID.randomUUID(), SOURCE_HASH, PREPARED_HASH, java.util.Collections.nCopies(201, row));
        assertThatThrownBy(() -> service.importMasters("categories", tenant, company, tooMany)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.importMasters("categories", "other-tenant", company, command(row))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.importMasters("categories", tenant, UUID.randomUUID(), command(row))).isInstanceOf(BadRequestException.class);
        authenticate("FINANCE_CONTROLLER");
        assertThatThrownBy(() -> service.importMasters("categories", tenant, company, command(row))).isInstanceOf(AccessDeniedException.class);
        assertThat(count("material_groups")).isZero();
    }
    private MasterRow unit(String source, String label) {
        ObjectNode p = mapper.createObjectNode().put("code", "1C-UOM-" + source).put("name", label)
            .put("measurementNameShort", label).put("measurementNameLong", label)
            .put("measurementNameShortRu", label).put("measurementNameLongRu", label).put("unitType", "MAIN");
        return new MasterRow(source, "Catalog41", null, p);
    }
    private MasterRow group(String source, String code, String name) {
        return new MasterRow(source, "Catalog6295", null, mapper.createObjectNode().put("code", code).put("name", name).put("status", "ACTIVE"));
    }
    private MasterRow warehouse(String source, String name) {
        return new MasterRow(source, "Catalog85", null, mapper.createObjectNode().put("name", name).put("status", "ACTIVE"));
    }
    private MasterChunkRequest command(MasterRow... rows) { return new MasterChunkRequest(UUID.randomUUID(), UUID.randomUUID(), SOURCE_HASH, PREPARED_HASH, List.of(rows)); }
    private String marker(MasterRow row) { return CatalogMasterMigrationService.sourceMarker(PREPARED_HASH, row); }
    private <T extends BusinessEntity> T base(T entity, String code, UUID companyId) {
        entity.setTenantId(tenant); entity.setCompanyId(companyId); entity.setCode(code); entity.setName(code); entity.setStatus("ACTIVE"); return entity;
    }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=? and company_id=?", Long.class, tenant, company); }
    private void authenticate(String role) {
        var auth = new UsernamePasswordAuthenticationToken("master-importer", null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.setDetails(new AccessScope.RequestScope(true)); SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

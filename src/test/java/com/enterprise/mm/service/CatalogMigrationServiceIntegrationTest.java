package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.payload.MaterialUpdateRequest;
import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogChunkRequest;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogRow;
import com.enterprise.mm.payload.MaterialAttributeRequest;
import com.enterprise.mm.payload.MaterialCreateRequest;
import com.enterprise.mm.security.AccessScope;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class CatalogMigrationServiceIntegrationTest {
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final String PREPARED_HASH = "b".repeat(64);
    private static final String SOURCE_1 = "1".repeat(32);
    private static final String SOURCE_2 = "2".repeat(32);

    @Autowired CatalogMigrationService service;
    @Autowired MaterialService materialService;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;

    private String tenantId;
    private UUID companyId;
    private UUID groupId;
    private UUID unitId;

    @BeforeEach
    void setUp() {
        tenantId = "catalog-migration-" + UUID.randomUUID();
        TenantContext.setTenant(tenantId);
        authenticate("ADMIN", true);
        transactions.executeWithoutResult(status -> {
            Company company = base(new Company(), "IMPORT-COMPANY", UUID.randomUUID());
            entityManager.persist(company);
            companyId = company.getId();
            company.setCompanyId(companyId);
            CompanyContext.setCompany(companyId);
            MaterialGroup group = base(new MaterialGroup(), "IMPORT-GROUP", companyId);
            entityManager.persist(group);
            groupId = group.getId();
            UnitOfMeasure unit = unit(companyId);
            entityManager.persist(unit);
            unitId = unit.getId();
        });
    }

    @AfterEach
    void tearDown() {
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void normalCreationRetainsAuditOutboxAndSourceMarkerAndReplayDoesNotDuplicate() {
        MaterialCreateRequest payload = payload("MAT-1");
        payload.setDescription("Original 1C label and classification");
        MaterialCatalogChunkRequest command = command(new MaterialCatalogRow(SOURCE_1, payload));

        var first = service.importMaterials(tenantId, companyId, command);
        var replay = service.importMaterials(tenantId, companyId, command);

        assertThat(replay).isEqualTo(first);
        assertThat(first.companyId()).isEqualTo(companyId);
        assertThat(first.tenantId()).isEqualTo(tenantId);
        assertThat(first.rows()).hasSize(1);
        assertThat(first.rows().getFirst().created()).isTrue();
        assertThat(count("materials")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(3);
        assertThat(count("audit_logs")).isEqualTo(3);
        assertThat(count("integration_events")).isEqualTo(2);
        String stored = jdbc.queryForObject("select description from materials where id = ?", String.class,
            first.rows().getFirst().materialId());
        assertThat(stored).contains(CatalogMigrationService.sourceMarker(PREPARED_HASH, SOURCE_1), payload.getDescription());
        assertThat(payload.getDescription()).isEqualTo("Original 1C label and classification");
    }

    @Test
    void multiplePersistenceContextBoundariesRetainAllWritesAndRepartitionedReplayMappings() {
        var rows = preparedRows(45);
        var command = command(rows.toArray(MaterialCatalogRow[]::new));

        var first = service.importMaterials(tenantId, companyId, command);
        assertThat(first.rows()).hasSize(45).allMatch(row -> row.created());
        assertImportedWrites(45, 47);
        for (int i = 0; i < rows.size(); i++) {
            String stored = jdbc.queryForObject("select description from materials where id = ?", String.class,
                first.rows().get(i).materialId());
            assertThat(stored).contains(CatalogMigrationService.sourceMarker(PREPARED_HASH, rows.get(i).sourceId()));
        }

        assertThat(service.importMaterials(tenantId, companyId, command)).isEqualTo(first);
        assertImportedWrites(45, 47);
        var firstPartition = new MaterialCatalogChunkRequest(command.importId(), UUID.randomUUID(), SOURCE_HASH,
            PREPARED_HASH, rows.subList(0, 21));
        var secondPartition = new MaterialCatalogChunkRequest(command.importId(), UUID.randomUUID(), SOURCE_HASH,
            PREPARED_HASH, rows.subList(21, 45));
        assertThat(service.importMaterials(tenantId, companyId, firstPartition).rows()).isEqualTo(first.rows().subList(0, 21));
        assertThat(service.importMaterials(tenantId, companyId, secondPartition).rows()).isEqualTo(first.rows().subList(21, 45));
        assertImportedWrites(45, 49); // Only the two new outer chunk idempotency records are added.
    }

    @Test
    void changedChunkOrRepartitionedSourcePayloadConflictsWithoutChangingExistingMaterial() {
        var row = new MaterialCatalogRow(SOURCE_1, payload("MAT-1"));
        var original = command(row);
        var first = service.importMaterials(tenantId, companyId, original);
        var moved = new MaterialCatalogChunkRequest(original.importId(), UUID.randomUUID(), SOURCE_HASH, PREPARED_HASH, List.of(row));

        assertThat(service.importMaterials(tenantId, companyId, moved).rows()).isEqualTo(first.rows());
        row.payload().setName("Changed source payload");
        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId, original))
            .isInstanceOf(ConflictException.class).hasMessageContaining("different request payload");
        var changedInNewChunk = new MaterialCatalogChunkRequest(original.importId(), UUID.randomUUID(), SOURCE_HASH, PREPARED_HASH, List.of(row));
        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId, changedInNewChunk))
            .isInstanceOf(ConflictException.class).hasMessageContaining("different request payload");
        assertThat(count("materials")).isEqualTo(1);
    }

    @Test
    void importIdCannotBeReboundToDifferentSourceOrPreparedManifest() {
        var command = command(new MaterialCatalogRow(SOURCE_1, payload("MAT-1")));
        service.importMaterials(tenantId, companyId, command);
        var changed = new MaterialCatalogChunkRequest(command.importId(), UUID.randomUUID(), "c".repeat(64), PREPARED_HASH,
            List.of(new MaterialCatalogRow(SOURCE_2, payload("MAT-2"))));
        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId, changed))
            .isInstanceOf(ConflictException.class);
        assertThat(count("materials")).isEqualTo(1);
    }

    @Test
    void laterBadRowRollsBackCreatedMaterialAuditOutboxAndAllIdempotencyRecords() {
        UUID inactiveGroup = transactions.execute(status -> {
            MaterialGroup group = base(new MaterialGroup(), "INACTIVE-GROUP", companyId);
            group.setStatus("INACTIVE");
            entityManager.persist(group);
            return group.getId();
        });
        var invalid = payload("MAT-2");
        invalid.setMaterialGroupId(inactiveGroup);
        var command = command(new MaterialCatalogRow(SOURCE_1, payload("MAT-1")), new MaterialCatalogRow(SOURCE_2, invalid));

        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId, command))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("ACTIVE");

        assertThat(count("materials")).isZero();
        assertThat(count("audit_logs")).isZero();
        assertThat(count("integration_events")).isZero();
        assertThat(count("idempotency_records")).isZero();
    }

    @Test
    void invalidRowAfterPersistenceContextBoundaryRollsBackTheEntireChunk() {
        UUID inactiveGroup = transactions.execute(status -> {
            MaterialGroup group = base(new MaterialGroup(), "LATE-INACTIVE-GROUP", companyId);
            group.setStatus("INACTIVE");
            entityManager.persist(group);
            return group.getId();
        });
        var rows = preparedRows(21);
        rows.get(20).payload().setMaterialGroupId(inactiveGroup);

        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId,
            command(rows.toArray(MaterialCatalogRow[]::new))))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("ACTIVE");

        assertImportedWrites(0, 0);
    }

    @Test
    void globalAdminCannotUseOtherCompanyOrOtherTenantUnitAndGroupIds() {
        UUID otherCompany = UUID.randomUUID();
        UUID foreignUnit = transactions.execute(status -> {
            UnitOfMeasure unit = unit(otherCompany);
            entityManager.persist(unit);
            return unit.getId();
        });
        var withForeignUnit = payload("MAT-1");
        withForeignUnit.setUnitOfMeasureId(foreignUnit);
        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId,
            command(new MaterialCatalogRow(SOURCE_1, withForeignUnit))))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("unit must belong");

        UUID foreignGroup = transactions.execute(status -> {
            MaterialGroup group = base(new MaterialGroup(), "FOREIGN-GROUP", companyId);
            group.setTenantId("different-tenant");
            entityManager.persist(group);
            return group.getId();
        });
        var withForeignGroup = payload("MAT-2");
        withForeignGroup.setMaterialGroupId(foreignGroup);
        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId,
            command(new MaterialCatalogRow(SOURCE_2, withForeignGroup))))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("group must belong");
        assertThat(count("materials")).isZero();
        assertThat(count("idempotency_records")).isZero();
    }

    @Test
    void existingSingleImporterMaterialCanBeAdoptedOnlyByExactSourceMarkerAndReferences() {
        var matching = payload("MAT-1");
        matching.setDescription(CatalogMigrationService.sourceMarker(PREPARED_HASH, SOURCE_1));
        var existing = materialService.create(matching);

        var response = service.importMaterials(tenantId, companyId, command(new MaterialCatalogRow(SOURCE_1, payload("MAT-1"))));
        assertThat(response.rows().getFirst().materialId()).isEqualTo(existing.getId());
        assertThat(response.rows().getFirst().created()).isFalse();
        assertThat(count("materials")).isEqualTo(1);
        assertThat(count("audit_logs")).isEqualTo(3);

        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId,
            command(new MaterialCatalogRow(SOURCE_2, payload("MAT-1")))))
            .isInstanceOf(ConflictException.class).hasMessageContaining("source identity");
        assertThat(count("materials")).isEqualTo(1);
    }

    @Test
    void explicitScopeBoundsAndDuplicateSourceOrCodesAreRequired() {
        var row = new MaterialCatalogRow(SOURCE_1, payload("MAT-1"));
        assertThatThrownBy(() -> service.importMaterials(null, companyId, command(row))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.importMaterials(tenantId, UUID.randomUUID(), command(row))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.importMaterials(tenantId, CompanyContext.DEFAULT_COMPANY_ID, command(row)))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId, command(row, row)))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("duplicate");
        var duplicateCode = new MaterialCatalogRow(SOURCE_2, payload("mat-1"));
        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId, command(row, duplicateCode)))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("duplicate");
        var oversized = new MaterialCatalogChunkRequest(UUID.randomUUID(), UUID.randomUUID(), SOURCE_HASH, PREPARED_HASH,
            IntStream.range(0, 201).mapToObj(i -> new MaterialCatalogRow(String.format("%032x", i), payload("MAT-" + i))).toList());
        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId, oversized))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("1 to 200");
        assertThat(count("materials")).isZero();
    }

    @Test
    void onlyAdminOrMmManagerCanImport() {
        authenticate("FINANCE_CONTROLLER", false);
        assertThatThrownBy(() -> service.importMaterials(tenantId, companyId,
            command(new MaterialCatalogRow(SOURCE_1, payload("MAT-1")))))
            .isInstanceOf(AccessDeniedException.class);
        authenticate("MM_MANAGER", false);
        assertThat(service.importMaterials(tenantId, companyId,
            command(new MaterialCatalogRow(SOURCE_1, payload("MAT-1")))).rows()).hasSize(1);
    }

    @Test
    void simultaneousRetrySerializesBeforeIdempotencyLookup() throws Exception {
        var request = command(preparedRows(21).toArray(MaterialCatalogRow[]::new));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogChunkResponse> call = () -> {
                TenantContext.setTenant(tenantId);
                CompanyContext.setCompany(companyId);
                authenticate("ADMIN", true);
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return service.importMaterials(tenantId, companyId, request);
                } finally {
                    SecurityContextHolder.clearContext();
                    TenantContext.clear();
                    CompanyContext.clear();
                }
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertImportedWrites(21, 23);
    }

    @Test
    void importedInventorySubtypesSurviveLegacyEditsAndClearOnServiceTransition() {
        var payload = payload("EQUIPMENT");
        payload.setMaterialSubtype(MaterialSubtype.EQUIPMENT);
        var imported = service.importMaterials(tenantId, companyId, command(new MaterialCatalogRow(SOURCE_1, payload)));
        UUID id = imported.rows().getFirst().materialId();
        assertThat(materialService.get(id).getMaterialSubtype()).isEqualTo(MaterialSubtype.EQUIPMENT);
        var update = new MaterialUpdateRequest();
        update.setCode(payload.getCode());
        update.setName(payload.getName());
        update.setMaterialGroupId(groupId);
        update.setUnitOfMeasureId(unitId);
        update.setMaterialType(MaterialType.TMB);
        update.setTrackingMode(com.enterprise.mm.enums.TrackingMode.QUANTITY);
        update.setBatchTrackingPolicy(com.enterprise.mm.enums.BatchTrackingPolicy.NONE);
        update.setSerialTrackingPolicy(com.enterprise.mm.enums.SerialTrackingPolicy.NONE);
        assertThat(materialService.update(id, update).getMaterialSubtype()).isEqualTo(MaterialSubtype.EQUIPMENT);
        update.setMaterialSubtype(MaterialSubtype.ANIMALS);
        var changed = materialService.update(id, update);
        assertThat(changed.getMaterialSubtype()).isEqualTo(MaterialSubtype.ANIMALS);
        assertThat(changed.getMaterialType()).isEqualTo(MaterialType.TMB);
        update.setMaterialType(MaterialType.SERVICES);
        update.setMaterialSubtype(null);
        assertThat(materialService.update(id, update).getMaterialSubtype()).isNull();
    }

    private MaterialCatalogChunkRequest command(MaterialCatalogRow... rows) {
        return new MaterialCatalogChunkRequest(UUID.randomUUID(), UUID.randomUUID(), SOURCE_HASH, PREPARED_HASH, List.of(rows));
    }

    private MaterialCreateRequest payload(String code) {
        var request = new MaterialCreateRequest();
        request.setCode(code);
        request.setName("1C material " + code);
        request.setMaterialGroupId(groupId);
        request.setUnitOfMeasureId(unitId);
        request.setMaterialType(MaterialType.TMB);
        return request;
    }

    private List<MaterialCatalogRow> preparedRows(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> {
            String sourceId = String.format("%032x", i);
            MaterialCreateRequest request = payload("BOUNDARY-MAT-" + i);
            request.setAttributes(List.of(new MaterialAttributeRequest("1C source " + tenantId, sourceId)));
            return new MaterialCatalogRow(sourceId, request);
        }).toList();
    }

    private void assertImportedWrites(int materialCount, int idempotencyCount) {
        assertThat(count("materials")).isEqualTo(materialCount);
        assertThat(count("material_valuation_method_history")).isEqualTo(materialCount);
        assertThat(count("audit_logs")).isEqualTo(3L * materialCount);
        assertThat(count("integration_events")).isEqualTo(2L * materialCount);
        assertThat(count("idempotency_records")).isEqualTo(idempotencyCount);
        assertThat(jdbc.queryForObject("select count(*) from material_attributes where attribute_key = ?",
            Long.class, "1C source " + tenantId)).isEqualTo((long) materialCount);
        assertThat(jdbc.queryForObject("""
            select count(*) from material_attributes attributes
            join materials material on material.id = attributes.material_id
            where material.tenant_id = ? and material.company_id = ? and attributes.is_deleted = false
            """, Long.class, tenantId, companyId)).isEqualTo((long) materialCount);
    }

    private UnitOfMeasure unit(UUID company) {
        UnitOfMeasure unit = base(new UnitOfMeasure(), "UNIT-" + UUID.randomUUID(), company);
        unit.setMeasurementNameShort("kg");
        unit.setMeasurementNameLong("Kilogram");
        unit.setUnitType(MeasurementUnitType.WEIGHT);
        return unit;
    }

    private <T extends BusinessEntity> T base(T entity, String code, UUID company) {
        entity.setTenantId(tenantId);
        entity.setCompanyId(company);
        entity.setCode(code);
        entity.setName(code);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ? and company_id = ?",
            Long.class, tenantId, companyId);
    }

    private void authenticate(String role, boolean globalAdmin) {
        var authentication = new UsernamePasswordAuthenticationToken("catalog-importer", null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        if (globalAdmin) {
            authentication.setDetails(new AccessScope.RequestScope(true));
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

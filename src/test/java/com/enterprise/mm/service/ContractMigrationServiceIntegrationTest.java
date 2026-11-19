package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.security.AccessScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.*;
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

@SpringBootTest
@ActiveProfiles("test")
class ContractMigrationServiceIntegrationTest {
    static final String SOURCE = "a".repeat(64), PREPARED = "b".repeat(64);
    @Autowired ContractMigrationService service;
    @Autowired ContractSpecificationService specificationService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    String tenant;
    UUID company, supplier;

    @BeforeEach void setup() {
        // Match the baseline PostgreSQL constraint absent from Hibernate's test DDL.
        jdbc.execute("alter table contract_specifications alter column service_limit set not null");
        tenant = "contract-import-" + UUID.randomUUID();
        TenantContext.setTenant(tenant);
        authenticate("ADMIN");
        company = tx.execute(status -> {
            Company value = base(new Company(), UUID.randomUUID());
            em.persist(value); value.setCompanyId(value.getId()); return value.getId();
        });
        CompanyContext.setCompany(company);
        supplier = tx.execute(status -> {
            Supplier value = base(new Supplier(), company);
            value.setStatus("NEW"); em.persist(value); return value.getId();
        });
    }

    @AfterEach void cleanup() {
        jdbc.execute("alter table contract_specifications alter column service_limit drop not null");
        AccessScope.clearRequestedCompanyScope(); SecurityContextHolder.clearContext();
        TenantContext.clear(); CompanyContext.clear();
    }

    @Test void exactReplayRepartitionAndUnknownResponseRecoveryCreateOneDraft() {
        var row = contract(1); var command = command(row);
        var first = service.importChunk("contracts", tenant, company, command);
        assertThat(service.importChunk("contracts", tenant, company, command)).isEqualTo(first);
        var repartition = new MasterChunkRequest(command.importId(), UUID.randomUUID(), SOURCE, PREPARED, command.rows());
        assertThat(service.importChunk("contracts", tenant, company, repartition).rows()).isEqualTo(first.rows());
        UUID id = first.rows().getFirst().masterId();
        var recovered = new MasterRow(row.sourceId(), row.sourceEntityType(), id, row.payload());
        assertThat(service.importChunk("contracts", tenant, company, command(recovered)).rows().getFirst().created()).isFalse();
        assertThat(count("contracts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select limit_amount from contracts where id=?", BigDecimal.class, id))
            .isEqualByComparingTo("12345678901234.5678");
        assertThat(jdbc.queryForObject("select used_amount from contracts where id=?", BigDecimal.class, id)).isZero();
        assertThat(jdbc.queryForObject("select status from contracts where id=?", String.class, id)).isEqualTo("DRAFT");
        assertThat(count("stock_movements")).isZero();
        assertThat(jdbc.queryForObject("select status from suppliers where id=?", String.class, supplier)).isEqualTo("NEW");
    }

    @Test void conflictingSourceOrChangedRecoveryPayloadIsRejected() {
        var row = contract(1); var command = command(row);
        service.importChunk("contracts", tenant, company, command);
        payload(row).put("limitAmount", "999.0000");
        assertThatThrownBy(() -> service.importChunk("contracts", tenant, company, command))
            .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.importChunk("contracts", tenant, company, command(row)))
            .isInstanceOf(ConflictException.class);
        assertThat(count("contracts")).isEqualTo(1);
    }

    @Test void dependencyFailureRollsBackTheWholeChunkAndJournal() {
        var first = contract(1); var second = contract(2);
        payload(second).put("supplierId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.importChunk("contracts", tenant, company, command(first, second)))
            .isInstanceOf(BadRequestException.class);
        assertThat(count("contracts")).isZero();
        assertThat(count("idempotency_records")).isZero();
        assertThat(count("audit_logs")).isZero();
    }

    @Test void wrongScopeRoleAndForeignSupplierAreRejected() {
        assertThatThrownBy(() -> service.importChunk("contracts", "foreign", company, command(contract(1))))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.capabilities(tenant, CompanyContext.DEFAULT_COMPANY_ID))
            .isInstanceOf(BadRequestException.class);
        UUID foreign = tx.execute(status -> {
            Supplier value = base(new Supplier(), UUID.randomUUID()); em.persist(value); return value.getId();
        });
        var row = contract(1); payload(row).put("supplierId", foreign.toString());
        assertThatThrownBy(() -> service.importChunk("contracts", tenant, company, command(row)))
            .isInstanceOf(BadRequestException.class);
        authenticate("WAREHOUSE_MANAGER");
        assertThatThrownBy(() -> service.importChunk("contracts", tenant, company, command(contract(1))))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(count("contracts")).isZero();
    }

    @Test void missingCurrencyStaysNullAndPrecisionLossOrActivationCannotBeImported() {
        var unknown = contract(1); payload(unknown).putNull("currencyCode");
        UUID id = service.importChunk("contracts", tenant, company, command(unknown)).rows().getFirst().masterId();
        assertThat(jdbc.queryForObject("select currency_code from contracts where id=?", String.class, id)).isNull();
        for (String field : List.of("status", "usedAmount", "limitAmount")) {
            var invalid = contract(2);
            payload(invalid).put(field, field.equals("status") ? "ACTIVE" : field.equals("limitAmount") ? "0.00001" : "5");
            assertThatThrownBy(() -> service.importChunk("contracts", tenant, company, command(invalid)))
                .isInstanceOf(BadRequestException.class);
        }
        var omitted = contract(3); payload(omitted).remove("currencyCode");
        assertThatThrownBy(() -> service.importChunk("contracts", tenant, company, command(omitted)))
            .isInstanceOf(BadRequestException.class);
    }

    @Test void addendumIsLinkedButNeverPostedOrAppliedToTheParent() {
        UUID parent = service.importChunk("contracts", tenant, company, command(contract(1))).rows().getFirst().masterId();
        var row = row(2, "Catalog9560");
        payload(row).put("contractId", parent.toString()).put("addendumNumber", "A-2")
            .put("addendumDate", "2025-03-01").put("addendumType", "LIMIT_CHANGE")
            .put("newLimitAmount", "100.1234").put("limitDelta", "0.0000");
        var command = command(row);
        UUID id = service.importChunk("addendums", tenant, company, command).rows().getFirst().masterId();
        assertThat(service.importChunk("addendums", tenant, company, command).rows().getFirst().masterId()).isEqualTo(id);
        assertThat(jdbc.queryForObject("select posted_at from contract_addendums where id=?", Object.class, id)).isNull();
        assertThat(jdbc.queryForObject("select status from contract_addendums where id=?", String.class, id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select limit_amount from contracts where id=?", BigDecimal.class, parent))
            .isEqualByComparingTo("12345678901234.5678");
        var noDate = row(3, "Catalog9560"); payload(noDate).put("contractId", parent.toString());
        assertThatThrownBy(() -> service.importChunk("addendums", tenant, company, command(noDate)))
            .isInstanceOf(BadRequestException.class);
    }

    @Test void mappedMaterialSpecificationPreservesSevenDecimalQuantityWithoutStock() {
        UUID parent = service.importChunk("contracts", tenant, company, command(contract(1))).rows().getFirst().masterId();
        UUID material = tx.execute(status -> {
            Material value = base(new Material(), company); em.persist(value); return value.getId();
        });
        var row = row(2, "Document9197Materials");
        payload(row).put("contractId", parent.toString()).put("materialId", material.toString())
            .put("lineType", "MATERIAL").put("quantityLimit", "123.1234567").put("price", "1.2345");
        UUID id = service.importChunk("specifications", tenant, company, command(row)).rows().getFirst().masterId();
        assertThat(service.importChunk("specifications", tenant, company, command(row)).rows().getFirst().created()).isFalse();
        assertThat(jdbc.queryForObject("select quantity_limit from contract_specifications where id=?", BigDecimal.class, id))
            .isEqualByComparingTo("123.1234567");
        assertThat(jdbc.queryForObject("select service_limit from contract_specifications where id=?", BigDecimal.class, id))
            .isZero();
        assertThat(count("stock_movements")).isZero();
        var foreign = row(3, "Document9197Materials");
        payload(foreign).setAll(payload(row)); payload(foreign).put("code", "1C-DOCUMENT9197MATERIALS-" + foreign.sourceId())
            .put("description", "Source\n[1c-mm-migration:" + PREPARED + ":Document9197Materials:" + foreign.sourceId() + "]")
            .put("materialId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.importChunk("specifications", tenant, company, command(foreign)))
            .isInstanceOf(BadRequestException.class);
    }

    @Test void statusMappingReplaysExactlyAndPreservesMoneyLinksAndStock() {
        var imported = contract(10);
        UUID id = service.importChunk("contracts", tenant, company, command(imported)).rows().getFirst().masterId();
        var row = statusRow(imported, id, "ACTIVE");
        var request = command(row);
        var first = service.importStatusChunk(tenant, company, request);
        assertThat(service.importStatusChunk(tenant, company, request)).isEqualTo(first);
        assertThat(service.importStatusChunk(tenant, company, new MasterChunkRequest(request.importId(),
            UUID.randomUUID(), SOURCE, PREPARED, request.rows())).rows()).isEqualTo(first.rows());
        assertThat(jdbc.queryForObject("select status from contracts where id=?", String.class, id)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select limit_amount from contracts where id=?", BigDecimal.class, id))
            .isEqualByComparingTo("12345678901234.5678");
        assertThat(jdbc.queryForObject("select used_amount from contracts where id=?", BigDecimal.class, id)).isZero();
        assertThat(jdbc.queryForObject("select supplier_id from contracts where id=?", UUID.class, id)).isEqualTo(supplier);
        assertThat(jdbc.queryForObject("select status from suppliers where id=?", String.class, supplier)).isEqualTo("NEW");
        assertThat(count("stock_movements")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where tenant_id=? and action='LEGACY_STATUS_MAPPING'", Long.class, tenant)).isEqualTo(1);
        payload(row).put("status", "CLOSED").put("sourceCancelled", true);
        assertThatThrownBy(() -> service.importStatusChunk(tenant, company, request)).isInstanceOf(ConflictException.class);
    }

    @Test void statusMappingRequiresEvidenceAndValidityAndRollsBackWholeChunk() {
        var active = contract(11); var expired = contract(12);
        payload(expired).put("endDate", "2020-01-01");
        var imported = service.importChunk("contracts", tenant, company, command(active, expired));
        UUID a = imported.rows().get(0).masterId(), e = imported.rows().get(1).masterId();
        var activeRow = statusRow(active, a, "ACTIVE"); var expiredRow = statusRow(expired, e, "ACTIVE");
        assertThatThrownBy(() -> service.importStatusChunk(tenant, company, command(activeRow, expiredRow)))
            .isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("select status from contracts where id=?", String.class, a)).isEqualTo("DRAFT");
        payload(activeRow).put("sourcePosted", false);
        assertThatThrownBy(() -> service.importStatusChunk(tenant, company, command(activeRow)))
            .isInstanceOf(BadRequestException.class);
        payload(expiredRow).put("status", "EXPIRED");
        service.importStatusChunk(tenant, company, command(expiredRow));
        assertThat(jdbc.queryForObject("select status from contracts where id=?", String.class, e)).isEqualTo("EXPIRED");
        var cancelled = statusRow(active, a, "CLOSED");
        assertThatThrownBy(() -> service.importStatusChunk(tenant, company, command(cancelled)))
            .isInstanceOf(BadRequestException.class);
        payload(cancelled).put("sourceCancelled", true);
        service.importStatusChunk(tenant, company, command(cancelled));
        assertThat(jdbc.queryForObject("select status from contracts where id=?", String.class, a)).isEqualTo("CLOSED");
    }

    @Test void statusMappingRejectsChangedVersionScopeEvidenceAndNonAdmin() {
        var imported = contract(13);
        UUID id = service.importChunk("contracts", tenant, company, command(imported)).rows().getFirst().masterId();
        var stale = statusRow(imported, id, "ACTIVE");
        payload(stale).put("expectedVersion", 999);
        assertThatThrownBy(() -> service.importStatusChunk(tenant, company, command(stale)))
            .isInstanceOf(ConflictException.class);
        var row = statusRow(imported, id, "ACTIVE");
        payload(row).put("originalPreparedManifestSha256", "f".repeat(64));
        var wrongMarker = row;
        assertThatThrownBy(() -> service.importStatusChunk(tenant, company, command(wrongMarker)))
            .isInstanceOf(ConflictException.class);
        var valid = statusRow(imported, id, "ACTIVE");
        assertThatThrownBy(() -> service.importStatusChunk("foreign", company, command(valid)))
            .isInstanceOf(BadRequestException.class);
        payload(valid).put("limitAmount", "0");
        assertThatThrownBy(() -> service.importStatusChunk(tenant, company, command(valid)))
            .isInstanceOf(BadRequestException.class);
        authenticate("WAREHOUSE_MANAGER");
        assertThatThrownBy(() -> service.importStatusChunk(tenant, company, command(valid)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test void linesImportSupportsActiveParentGroupAndExactReplayWithoutStockEffects() {
        UUID parent = importedParent("ACTIVE");
        UUID group = tx.execute(status -> { var g = base(new MaterialGroup(), company); em.persist(g); return g.getId(); });
        UUID unit = tx.execute(status -> { var u = base(new UnitOfMeasure(), company); em.persist(u); return u.getId(); });
        var row = line(101, parent, "ACTIVE");
        payload(row).put("materialGroupId", group.toString()).put("unitOfMeasureId", unit.toString());
        var request = linesCommand(row);
        var result = service.importChunk("lines", tenant, company, request);
        assertThat(result.kind()).isEqualTo("lines");
        assertThat(service.capabilities(tenant, company)).containsEntry("contractLineVersion", 1);
        assertThat(service.importChunk("lines", tenant, company, request)).isEqualTo(result);
        assertThat(service.importChunk("lines", tenant, company, linesCommand(row)).rows().getFirst().created()).isFalse();
        UUID id = result.rows().getFirst().masterId();
        assertThat(jdbc.queryForObject("select material_group_id from contract_specifications where id=?", UUID.class, id)).isEqualTo(group);
        assertThat(jdbc.queryForObject("select unit_of_measure_id from contract_specifications where id=?", UUID.class, id)).isEqualTo(unit);
        assertThat(jdbc.queryForObject("select status from contracts where id=?", String.class, parent)).isEqualTo("ACTIVE");
        assertThat(count("stock_movements")).isZero();
        payload(row).put("quantityLimit", "11");
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(ConflictException.class);
    }

    @Test void linesImportKeepsUnresolvedDraftWithReviewEvidenceAndNullableSourceValues() {
        UUID parent = importedParent("CLOSED");
        var row = line(102, parent, "DRAFT");
        payload(row).put("reviewReason", "Source contains neither a material nor group").putNull("quantityLimit").putNull("price");
        UUID id = service.importChunk("lines", tenant, company, linesCommand(row)).rows().getFirst().masterId();
        assertThat(jdbc.queryForObject("select status from contract_specifications where id=?", String.class, id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select quantity_limit from contract_specifications where id=?", BigDecimal.class, id)).isNull();
        assertThat(jdbc.queryForObject("select description from contract_specifications where id=?", String.class, id)).contains("Source evidence");
        payload(row).remove("reviewReason");
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(BadRequestException.class);
    }

    @Test void linesImportRejectsParentEvidenceForeignReferencesAndForbiddenFields() {
        UUID parent = importedParent("EXPIRED");
        UUID foreign = tx.execute(status -> { var g = base(new MaterialGroup(), UUID.randomUUID()); em.persist(g); return g.getId(); });
        var row = line(103, parent, "DRAFT"); payload(row).put("reviewReason", "Check source");
        payload(row).put("materialGroupId", foreign.toString());
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(BadRequestException.class);
        payload(row).remove("materialGroupId"); payload(row).put("originalPreparedManifestSha256", "f".repeat(64));
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(ConflictException.class);
        payload(row).put("originalPreparedManifestSha256", PREPARED).put("sourceRfqItemId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(BadRequestException.class);
        assertThat(count("contract_specifications")).isZero();
    }

    @Test void linesImportRollsBackBatchAndCanRetryAfterInvalidReferenceIsFixed() {
        UUID parent = importedParent("ACTIVE");
        var first = line(104, parent, "DRAFT"); payload(first).put("reviewReason", "Unknown material");
        var second = line(105, parent, "DRAFT"); payload(second).put("reviewReason", "Unknown group").put("materialGroupId", UUID.randomUUID().toString());
        var request = linesCommand(first, second);
        long journal = count("idempotency_records"), audit = count("audit_logs");
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, request)).isInstanceOf(BadRequestException.class);
        assertThat(count("contract_specifications")).isZero();
        assertThat(count("idempotency_records")).isEqualTo(journal); assertThat(count("audit_logs")).isEqualTo(audit);
        payload(second).remove("materialGroupId");
        assertThat(service.importChunk("lines", tenant, company, request).rows()).hasSize(2);
    }

    @Test void linesImportRejectsPrecisionLossAndUnresolvedActivationAndNonAdmin() {
        UUID parent = importedParent("ACTIVE");
        var row = line(106, parent, "DRAFT"); payload(row).put("reviewReason", "Unknown material").put("quantityLimit", "1.12345678");
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(BadRequestException.class);
        payload(row).put("quantityLimit", "1").put("status", "ACTIVE");
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(BadRequestException.class);
        authenticate("WAREHOUSE_MANAGER");
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(AccessDeniedException.class);
    }

    @Test void normalSpecificationCreateValidatesGroupUnitAndParentScopeAndRejectsUnresolvedDraft() {
        UUID parent = importedParent("ACTIVE");
        UUID group = tx.execute(status -> { var g = base(new MaterialGroup(), company); em.persist(g); return g.getId(); });
        UUID unit = tx.execute(status -> { var u = base(new UnitOfMeasure(), company); em.persist(u); return u.getId(); });
        var dto = mapper.convertValue(linePayloadForNormal(parent, group, unit), com.enterprise.mm.payload.ContractSpecificationCreateRequest.class);
        assertThat(dto.getServiceLimit()).isNull();
        var created = specificationService.create(dto);
        assertThat(created.getServiceLimit()).isZero();
        var update = mapper.convertValue(linePayloadForNormal(parent, group, unit), com.enterprise.mm.payload.ContractSpecificationUpdateRequest.class);
        update.setCode(created.getCode());
        update.setStatus(null);
        var updated = specificationService.update(created.getId(), update);
        assertThat(updated.getServiceLimit()).isZero();
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
        assertThat(mapper.valueToTree(created).path("materialGroupId").asText()).isEqualTo(group.toString());
        dto.setContractId(UUID.randomUUID());
        assertThatThrownBy(() -> specificationService.create(dto)).isInstanceOf(BusinessException.class);
        dto.setContractId(parent); dto.setStatus("DRAFT");
        var missing = mapper.valueToTree(dto); ((ObjectNode) missing).remove("materialGroupId");
        var unresolved = mapper.convertValue(missing, com.enterprise.mm.payload.ContractSpecificationCreateRequest.class);
        assertThatThrownBy(() -> specificationService.create(unresolved)).isInstanceOf(BusinessException.class);
        var foreign = tx.execute(status -> { var u = base(new UnitOfMeasure(), UUID.randomUUID()); em.persist(u); return u.getId(); });
        ((ObjectNode) missing).put("materialGroupId", group.toString()).put("unitOfMeasureId", foreign.toString());
        var foreignUnit = mapper.convertValue(missing, com.enterprise.mm.payload.ContractSpecificationCreateRequest.class);
        assertThatThrownBy(() -> specificationService.create(foreignUnit)).isInstanceOf(BusinessException.class);
    }

    @Test void normalGroupSpecificationRejectsInvalidPriceAndInactiveReferences() {
        UUID parent = importedParent("ACTIVE");
        UUID group = tx.execute(status -> { var g = base(new MaterialGroup(), company); em.persist(g); return g.getId(); });
        UUID unit = tx.execute(status -> { var u = base(new UnitOfMeasure(), company); em.persist(u); return u.getId(); });
        var dto = mapper.convertValue(linePayloadForNormal(parent, group, unit), com.enterprise.mm.payload.ContractSpecificationCreateRequest.class);
        dto.setPrice(null);
        assertThatThrownBy(() -> specificationService.create(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("price");
        dto.setPrice(BigDecimal.ONE.negate());
        assertThatThrownBy(() -> specificationService.create(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("price");
        dto.setPrice(BigDecimal.ZERO);
        tx.executeWithoutResult(status -> em.find(MaterialGroup.class, group).setStatus("INACTIVE"));
        assertThatThrownBy(() -> specificationService.create(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("ACTIVE");
        tx.executeWithoutResult(status -> {
            em.find(MaterialGroup.class, group).setStatus("ACTIVE");
            em.find(UnitOfMeasure.class, unit).setStatus("INACTIVE");
        });
        assertThatThrownBy(() -> specificationService.create(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("ACTIVE");
    }
    ObjectNode linePayloadForNormal(UUID parent, UUID group, UUID unit) {
        return mapper.createObjectNode().put("name", "Group contract line").put("status", "ACTIVE").put("contractId", parent.toString())
            .put("lineType", "MATERIAL").put("materialGroupId", group.toString()).put("unitOfMeasureId", unit.toString())
            .put("quantityLimit", "10").put("price", "1");
    }

    @Test void exactLineRecoveryRetainsDerivedMaterialTypeAndRejectsChangedReviewEvidence() {
        UUID parent = importedParent("ACTIVE");
        UUID material = tx.execute(status -> { var m = base(new Material(), company); em.persist(m); return m.getId(); });
        var row = line(107, parent, "DRAFT");
        payload(row).put("reviewReason", "Source financial mismatch").put("materialId", material.toString()).putNull("materialTypeSnapshot");
        UUID id = service.importChunk("lines", tenant, company, linesCommand(row)).rows().getFirst().masterId();
        assertThat(service.importChunk("lines", tenant, company, linesCommand(row)).rows().getFirst().masterId()).isEqualTo(id);
        payload(row).put("reviewReason", "Different review explanation");
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(ConflictException.class);
    }

    @Test void unresolvedImportRequiresEvidenceBeyondTheMarker() {
        UUID parent = importedParent("ACTIVE");
        var row = line(108, parent, "DRAFT");
        payload(row).put("reviewReason", "Unresolved source").put("description", "[1c-mm-migration:" + "c".repeat(64) + ":Document9197Materials:" + row.sourceId() + "]");
        assertThatThrownBy(() -> service.importChunk("lines", tenant, company, linesCommand(row))).isInstanceOf(BadRequestException.class);
    }

    UUID importedParent(String status) {
        UUID id = service.importChunk("contracts", tenant, company, command(contract(100))).rows().getFirst().masterId();
        tx.executeWithoutResult(txStatus -> em.find(Contract.class, id).setStatus(status));
        return id;
    }
    MasterRow line(int number, UUID parent, String status) {
        var row = row(number, "Document9197Materials");
        payload(row).put("description", "Source evidence\n[1c-mm-migration:" + "c".repeat(64) + ":Document9197Materials:" + row.sourceId() + "]")
            .put("contractId", parent.toString()).put("lineType", "MATERIAL").put("status", status)
            .put("originalPreparedManifestSha256", PREPARED).put("quantityLimit", "10.1234567").put("price", "1.2345");
        return row;
    }
    MasterChunkRequest linesCommand(MasterRow... rows) {
        return new MasterChunkRequest(UUID.randomUUID(), UUID.randomUUID(), SOURCE, "c".repeat(64), List.of(rows));
    }

    MasterRow statusRow(MasterRow imported, UUID id, String status) {
        long version = jdbc.queryForObject("select version from contracts where id=?", Long.class, id);
        var payload = mapper.createObjectNode().put("status", status).put("expectedVersion", version)
            .put("originalPreparedManifestSha256", PREPARED).put("sourcePosted", true).put("sourceCancelled", false)
            .put("registrationType", "Document4974").put("registrationSourceId", "9".repeat(32));
        return new MasterRow(imported.sourceId(), "Catalog34", id, payload);
    }

    MasterRow contract(int number) {
        var row = row(number, "Catalog34");
        payload(row).put("supplierId", supplier.toString()).put("contractNumber", "C-" + number)
            .put("currencyCode", "UZS").put("limitAmount", "12345678901234.5678");
        return row;
    }
    MasterRow row(int number, String type) {
        String sourceId = "%032x".formatted(number);
        var payload = mapper.createObjectNode().put("code", "1C-" + type.toUpperCase(Locale.ROOT) + "-" + sourceId)
            .put("name", "Source contract " + number).put("status", "DRAFT")
            .put("description", "Source evidence\n[1c-mm-migration:" + PREPARED + ":" + type + ":" + sourceId + "]");
        return new MasterRow(sourceId, type, null, payload);
    }
    ObjectNode payload(MasterRow row) { return (ObjectNode) row.payload(); }
    MasterChunkRequest command(MasterRow... rows) {
        return new MasterChunkRequest(UUID.randomUUID(), UUID.randomUUID(), SOURCE, PREPARED, List.of(rows));
    }
    long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id=?", Long.class, tenant);
    }
    <T extends BusinessEntity> T base(T entity, UUID companyId) {
        entity.setCode("TEST-" + UUID.randomUUID()); entity.setName("Test entity");
        entity.setStatus("ACTIVE"); entity.setTenantId(tenant); entity.setCompanyId(companyId); return entity;
    }
    void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "migration-test", "unused", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}

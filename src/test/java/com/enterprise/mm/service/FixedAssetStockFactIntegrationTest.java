package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real committed stock/cost/audit transactions; no mocked stock mutation or assignment resolver. */
@SpringBootTest(properties = {"app.seed.enabled=false", "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false",
    "mm.pilot.enabled-capabilities=FIXED_ASSET_EXECUTION,SERIALIZED_EXECUTION", "mm.pilot.code-enabled-capabilities="})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@EntityScan(basePackages = {"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
class FixedAssetStockFactIntegrationTest {
    @Autowired FixedAssetStockFactService service;
    @Autowired WarehouseStockFactService materialFacts;
    @Autowired StockMovementService stock;
    @Autowired CompanyRepository companies;
    @Autowired WarehouseRepository warehouses;
    @Autowired EmployeeRepository employees;
    @Autowired MaterialRepository materials;
    @Autowired UnitOfMeasureRepository units;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.enterprise.mm.warehouse.reports.WarehouseReportService reports;
    UUID company, warehouse, otherWarehouse;
    String tenant, actor, otherActor;
    UnitOfMeasure unit;
    Material asset, tmb;

    @BeforeEach void setup() {
        tenant = "asset-facts-" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        var c = new Company(); c.setName("Quantity test factory"); c.setStatus("ACTIVE");
        company = companies.saveAndFlush(c).getId(); CompanyContext.setCompany(company);
        warehouse = warehouse("073.1"); otherWarehouse = warehouse("076");
        actor = employee("keeper-073", warehouse); otherActor = employee("keeper-076", otherWarehouse);
        authenticate(actor);
        unit = new UnitOfMeasure(); unit.setCode("EA"); unit.setName("Pieces"); unit.setStatus("ACTIVE");
        unit.setUnitType(MeasurementUnitType.QUANTITY); unit.setStockQuantityScale(0); unit = units.saveAndFlush(unit);
        asset = material("63640352", "Heat exchanger", MaterialType.FIXED_ASSET);
        tmb = material("TMB-001", "Bearing", MaterialType.TMB);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear(); }

    @Test void realHttpFlowSearchesByCodeAndNameAndIgnoresClientWarehouseSelection() throws Exception {
        String root = "/api/workspaces/warehouse-execution/manual/fixed-asset-stock";
        mvc.perform(get(root + "/context").with(user(actor).roles("WAREHOUSE_KEEPER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.warehouseId").value(warehouse.toString()));
        mvc.perform(get(root + "/materials").param("code", "6364").param("name", "EXCHANGER")
            .with(user(actor).roles("WAREHOUSE_KEEPER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].id").value(asset.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].name").isString());
        String receipt = """
            {"warehouseId":"%s","movementDate":"%s","lines":[{"materialId":"%s","quantity":5,"unitCost":100}]}
            """.formatted(otherWarehouse, LocalDate.now(), asset.getId());
        for (int i = 0; i < 2; i++) {
            mvc.perform(multipart(root + "/receipt").file(jsonPart(receipt)).with(user(actor).roles("WAREHOUSE_KEEPER"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", company).header("Idempotency-Key", "http-receipt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("POSTED"));
        }
        String issue = """
            {"warehouseId":"%s","movementDate":"%s","lines":[{"materialId":"%s","quantity":2}]}
            """.formatted(otherWarehouse, LocalDate.now(), asset.getId());
        mvc.perform(multipart(root + "/issue").file(jsonPart(issue)).with(user(actor).roles("WAREHOUSE_KEEPER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company).header("Idempotency-Key", "http-issue"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("POSTED"));
        mvc.perform(get(root + "/balances").with(user(actor).roles("WAREHOUSE_KEEPER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].materialId").value(asset.getId().toString()));
        TenantContext.setTenant(tenant); CompanyContext.setCompany(company); authenticate(actor);
        assertQuantity(asset, warehouse, "3"); assertQuantity(asset, otherWarehouse, "0");
    }

    @Test void actualHttpFiltersDenyReadersAndUnassignedKeepers() throws Exception {
        String root = "/api/workspaces/warehouse-execution/manual/fixed-asset-stock";
        mvc.perform(get(root + "/context").with(user(actor).roles("VIEWER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company)).andExpect(status().isForbidden());
        mvc.perform(get(root + "/context").with(user("unassigned").roles("WAREHOUSE_KEEPER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company)).andExpect(status().isForbidden());
        String request = """
            {"movementDate":"%s","lines":[{"materialId":"%s","quantity":1,"unitCost":100}]}
            """.formatted(LocalDate.now(), asset.getId());
        mvc.perform(multipart(root + "/receipt").file(jsonPart(request)).with(user(actor).roles("VIEWER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company).header("Idempotency-Key", "denied"))
            .andExpect(status().isForbidden());
        TenantContext.setTenant(tenant); CompanyContext.setCompany(company); authenticate(actor);
        assertQuantity(asset, warehouse, "0");
    }

    private MockMultipartFile jsonPart(String json) {
        return new MockMultipartFile("request", "request.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    @Test void receiptIssueAndReportsUseTheActorsWarehouseWithoutAnyBusinessDocument() {
        var receipt = service.record(request(asset, "5", "100"), null, "receipt", true);
        assertThat(receipt.status()).isEqualTo("POSTED");
        assertQuantity(asset, warehouse, "5");
        assertThat(service.context().warehouseId()).isEqualTo(warehouse);
        assertThat(service.issueOptions(asset.getId(), PickingMethod.FIFO, 0, 25).eligibleAvailableQuantity()).isEqualByComparingTo("5");
        assertThat(service.previewIssue(request(asset, "2", null)).warehouseId()).isEqualTo(warehouse);
        var issue = service.record(request(asset, "2", null), null, "issue", false);
        assertThat(issue.status()).isEqualTo("POSTED");
        assertQuantity(asset, warehouse, "3"); assertQuantity(asset, otherWarehouse, "0");
        assertThat(jdbc.queryForObject("select count(*) from fixed_assets where company_id=?", Long.class, company)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from material_units where company_id=?", Long.class, company)).isZero();
        assertThat(jdbc.queryForObject("select manual_operation_payload from stock_movement_documents where id=?", String.class, receipt.documentId()))
            .contains("FIXED_ASSET", "QUANTITY");
        assertThat(jdbc.queryForObject("select created_by from stock_movements where id=?", String.class, receipt.movementIds().getFirst())).isEqualTo(actor);
        var report = service.balances(LocalDate.now(), asset.getCode(), 0, 20);
        assertThat(report.content()).hasSize(1);
        assertThat(new BigDecimal(report.content().getFirst().closingQuantity())).isEqualByComparingTo("3");
    }

    @Test void codeNameSearchIsCaseInsensitiveAndExcludesOtherTypesDeletedInactiveAndForeignMasters() {
        var inactive = material("6364-INACTIVE", "Heat exchanger inactive", MaterialType.FIXED_ASSET);
        inactive.setStatus("INACTIVE"); materials.saveAndFlush(inactive);
        var deleted = material("6364-DELETED", "Heat exchanger deleted", MaterialType.FIXED_ASSET);
        deleted.softDelete(); materials.saveAndFlush(deleted);
        CompanyContext.setCompany(UUID.randomUUID()); material("63640352", "Heat exchanger foreign", MaterialType.FIXED_ASSET);
        CompanyContext.setCompany(company);
        assertThat(service.materials("  HEAT  ", null, null, PageRequest.of(0, 10)).content()).extracting(o -> o.id()).containsExactly(asset.getId());
        assertThat(service.materials(null, "6364", "EXCHANGER", PageRequest.of(0, 10)).content()).hasSize(1);
        assertThat(service.materials("TMB", null, null, PageRequest.of(0, 10)).content()).isEmpty();
        assertThat(service.materials(null, "bad", null, PageRequest.of(0, 10)).content()).isEmpty();
    }

    @Test void retryIsAppliedOnceAndChangedPayloadForSameKeyIsRejected() {
        var command = request(asset, "5", "100");
        var first = service.record(command, null, "same-key", true);
        var retry = service.record(command, null, "same-key", true);
        assertThat(retry.documentId()).isEqualTo(first.documentId()); assertQuantity(asset, warehouse, "5");
        var decimalRetry = service.record(request(asset, "5.00", "100"), null, "same-key", true);
        assertThat(decimalRetry.documentId()).isEqualTo(first.documentId()); assertQuantity(asset, warehouse, "5");
        assertThatThrownBy(() -> service.record(request(asset, "6", "100"), null, "same-key", true)).isInstanceOf(RuntimeException.class);
        assertQuantity(asset, warehouse, "5");
    }

    @Test void fifoIssueAcrossReceiptsKeepsExactBatchQuantitiesAndRemainingValue() {
        service.record(request(asset, "5", "100"), null, "batch-one", true);
        service.record(request(asset, "3", "200"), null, "batch-two", true);
        var result = service.record(request(asset, "6", null), null, "split-issue", false);
        assertThat(result.movementIds()).hasSize(2);
        assertQuantity(asset, warehouse, "2");
        assertThat(jdbc.queryForObject("select sum(total_value) from stock_balances where company_id=? and material_id=?",
            BigDecimal.class, company, asset.getId())).isEqualByComparingTo("400");
    }

    @Test void httpManualPreviewAndIssueUseExactSelectedBatchesAndRetryPostsOnce() throws Exception {
        service.record(request(asset, "5", "100"), null, "manual-old", true);
        service.record(request(asset, "3", "200"), null, "manual-new", true);
        var positions = service.issueOptions(asset.getId(), PickingMethod.FIFO, 0, 25).positions();
        UUID oldPosition = positions.getFirst().stockBalanceId(), newPosition = positions.getLast().stockBalanceId();
        String root = "/api/workspaces/warehouse-execution/manual/fixed-asset-stock";
        String command = """
            {"movementDate":"%s","lines":[{"materialId":"%s","quantity":3,
             "pickingMethod":"FIFO","allocationMode":"MANUAL","allocations":[
               {"stockBalanceId":"%s","quantity":2},
               {"stockBalanceId":"%s","quantity":1}]}]}
            """.formatted(LocalDate.now(), asset.getId(), newPosition, oldPosition);
        mvc.perform(post(root + "/issue-preview").contentType("application/json").content(command)
            .with(user(actor).roles("WAREHOUSE_KEEPER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.warehouseId").value(warehouse.toString()))
            .andExpect(jsonPath("$.data.lines[0].allocationMode").value("MANUAL"))
            .andExpect(jsonPath("$.data.lines[0].allocations[0].stockBalanceId").value(newPosition.toString()))
            .andExpect(jsonPath("$.data.lines[0].allocations[0].quantity").value(2))
            .andExpect(jsonPath("$.data.lines[0].allocations[1].stockBalanceId").value(oldPosition.toString()))
            .andExpect(jsonPath("$.data.lines[0].allocations[1].quantity").value(1));
        assertQuantity(asset, warehouse, "8");
        assertThat(jdbc.queryForObject("select count(*) from stock_movement_documents where company_id=?", Long.class, company)).isEqualTo(2);
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(multipart(root + "/issue").file(jsonPart(command))
                .with(user(actor).roles("WAREHOUSE_KEEPER"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", company).header("Idempotency-Key", "manual-issue"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("POSTED"))
                .andExpect(jsonPath("$.data.lineCount").value(2));
        }
        assertPositionQuantity(oldPosition, "4"); assertPositionQuantity(newPosition, "1");
        assertThat(jdbc.queryForObject("select sum(total_value) from stock_balances where company_id=? and material_id=?",
            BigDecimal.class, company, asset.getId())).isEqualByComparingTo("600");
        assertThat(jdbc.queryForObject("select count(*) from stock_movement_documents where company_id=?", Long.class, company)).isEqualTo(3);
        TenantContext.setTenant(tenant); CompanyContext.setCompany(company); authenticate(actor);
        var changed = manualRequest("3", StockAllocationMode.MANUAL,
            new WarehouseStockFactRequest.Allocation(oldPosition, new BigDecimal("3"), null));
        assertThatThrownBy(() -> service.record(changed, null, "manual-issue", false))
            .isInstanceOf(ConflictException.class).hasMessageContaining("different request payload");
        assertPositionQuantity(oldPosition, "4"); assertPositionQuantity(newPosition, "1");
    }

    @ParameterizedTest
    @EnumSource(PickingMethod.class)
    void explicitAutoPickingStillHonorsFifoAndLifo(PickingMethod method) {
        service.record(request(asset, "5", "100"), null, "auto-old", true);
        service.record(request(asset, "3", "200"), null, "auto-new", true);
        var positions = service.issueOptions(asset.getId(), PickingMethod.FIFO, 0, 25).positions();
        UUID oldPosition = positions.getFirst().stockBalanceId(), newPosition = positions.getLast().stockBalanceId();
        var command = new FixedAssetStockFactRequest(LocalDate.now(), null, null, List.of(
            new FixedAssetStockFactRequest.Line(asset.getId(), new BigDecimal("2"), null, method, StockAllocationMode.AUTO, null)));
        var preview = service.previewIssue(command).lines().getFirst();
        assertThat(preview.allocationMode()).isEqualTo(StockAllocationMode.AUTO);
        assertThat(preview.allocations()).singleElement().satisfies(a ->
            assertThat(a.stockBalanceId()).isEqualTo(method == PickingMethod.FIFO ? oldPosition : newPosition));
        service.record(command, null, "auto-issue", false);
        assertPositionQuantity(oldPosition, method == PickingMethod.FIFO ? "3" : "5");
        assertPositionQuantity(newPosition, method == PickingMethod.FIFO ? "3" : "1");
    }

    @Test void invalidManualSelectionsFailPreviewAndPostingWithoutChangingStock() {
        service.record(request(asset, "5", "100"), null, "validation-old", true);
        service.record(request(asset, "3", "200"), null, "validation-new", true);
        UUID ownPosition = service.issueOptions(asset.getId(), PickingMethod.FIFO, 0, 25).positions().getFirst().stockBalanceId();
        authenticate(otherActor);
        service.record(request(asset, "2", "100"), null, "validation-foreign", true);
        UUID foreignPosition = service.issueOptions(asset.getId(), PickingMethod.FIFO, 0, 25).positions().getFirst().stockBalanceId();
        authenticate(actor);
        var one = new WarehouseStockFactRequest.Allocation(ownPosition, BigDecimal.ONE, null);
        var invalid = List.of(
            manualRequest("1", StockAllocationMode.MANUAL),
            manualRequest("2", StockAllocationMode.MANUAL, one),
            manualRequest("6", StockAllocationMode.MANUAL, new WarehouseStockFactRequest.Allocation(ownPosition, new BigDecimal("6"), null)),
            manualRequest("2", StockAllocationMode.MANUAL, one, one),
            manualRequest("1", StockAllocationMode.MANUAL, new WarehouseStockFactRequest.Allocation(foreignPosition, BigDecimal.ONE, null)),
            manualRequest("1", StockAllocationMode.AUTO, one));
        var reasons = List.of("requires source quantities", "sum must equal", "exceeds executable",
            "only once", "outside", "does not accept");
        for (int i = 0; i < invalid.size(); i++) {
            var command = invalid.get(i);
            String key = "invalid-manual-" + i;
            assertThatThrownBy(() -> service.previewIssue(command))
                .isInstanceOfAny(BadRequestException.class, BusinessException.class)
                .hasMessageContaining(reasons.get(i));
            assertThatThrownBy(() -> service.record(command, null, key, false))
                .isInstanceOfAny(BadRequestException.class, BusinessException.class)
                .hasMessageContaining(reasons.get(i));
        }
        assertQuantity(asset, warehouse, "8"); assertQuantity(asset, otherWarehouse, "2");
        assertThat(jdbc.queryForObject("select count(*) from stock_movement_documents where company_id=?", Long.class, company)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"uz", "ru", "en"})
    void realHttpStockPickingErrorsAreLocalizedForFixedAssetPreviewAndIssue(String language) throws Exception {
        service.record(request(asset, "2", "100"), null, "localized-seed", true);
        var position = service.issueOptions(asset.getId(), PickingMethod.FIFO, 0, 25).positions().getFirst();
        String insufficient = switch (language) {
            case "uz" -> "So‘ralgan miqdorni chiqarish uchun mavjud qoldiq yetarli emas.";
            case "ru" -> "Недостаточно доступного остатка для выдачи указанного количества.";
            default -> "Not enough stock is available to issue the requested quantity.";
        };
        String unavailable = switch (language) {
            case "uz" -> "Tanlangan qoldiqdan chiqim qilish mumkin emas.";
            case "ru" -> "Выбранный остаток недоступен для выдачи.";
            default -> "The selected stock position is unavailable for issue.";
        };
        String automatic = """
            {"movementDate":"%s","lines":[{"materialId":"%s","quantity":3,"pickingMethod":"FIFO","allocationMode":"AUTO"}]}
            """.formatted(LocalDate.now(), asset.getId());
        assertFixedAssetIssueError(automatic, language, "STOCK_PICKING_INSUFFICIENT", insufficient);
        String manual = """
            {"movementDate":"%s","lines":[{"materialId":"%s","quantity":%s,"allocationMode":"MANUAL",
             "allocations":[{"stockBalanceId":"%s","quantity":%s}]}]}
            """;
        assertFixedAssetIssueError(manual.formatted(LocalDate.now(), asset.getId(), 3, position.stockBalanceId(), 3),
            language, "STOCK_PICKING_INSUFFICIENT", insufficient);
        jdbc.update("update stock_batches set status='BLOCKED' where id=?", position.stockBatchId());
        assertFixedAssetIssueError(manual.formatted(LocalDate.now(), asset.getId(), 1, position.stockBalanceId(), 1),
            language, "STOCK_PICKING_UNAVAILABLE", unavailable);
        assertQuantity(asset, warehouse, "2");
        assertThat(jdbc.queryForObject("select count(*) from stock_movement_documents where company_id=?", Long.class, company)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select total_value from stock_balances where id=?", BigDecimal.class, position.stockBalanceId()))
            .isEqualByComparingTo("200");
    }

    @Test void eachKeeperChangesOnlyTheirOwnWarehouseAndCannotSpendTheOthersStock() {
        service.record(request(asset, "5", "100"), null, "first", true);
        authenticate(otherActor);
        assertThat(service.context().warehouseId()).isEqualTo(otherWarehouse);
        assertThatThrownBy(() -> service.record(request(asset, "1", null), null, "foreign-issue", false)).isInstanceOf(RuntimeException.class);
        service.record(request(asset, "2", "100"), null, "second", true);
        service.record(request(asset, "1", null), null, "second-issue", false);
        assertQuantity(asset, warehouse, "5"); assertQuantity(asset, otherWarehouse, "1");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("factory-viewer", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var report = reports.generate(new com.enterprise.mm.warehouse.reports.WarehouseReport.Filter(
            com.enterprise.mm.warehouse.reports.WarehouseReport.Type.STOCK_BALANCES, LocalDate.now(), LocalDate.now(),
            null, null, null, null, 0, 20, null, MaterialType.FIXED_ASSET, null, null), false);
        assertThat(report.content()).hasSize(2);
        assertThat(report.content().stream().map(row -> new BigDecimal(row.closingQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("6");
    }

    @Test void missingAssignmentAndWrongCompanyAreDeniedBeforeMutation() {
        authenticate("unassigned");
        assertThatThrownBy(() -> service.record(request(asset, "1", "100"), null, "none", true))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        authenticate(actor); CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(() -> service.record(request(asset, "1", "100"), null, "foreign-company", true))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        CompanyContext.setCompany(company); assertQuantity(asset, warehouse, "0");
    }

    @Test void administratorStillUsesTheirPersonalAssignmentInsteadOfGlobalBypass() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "unused",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        service.record(request(asset, "1", "100"), null, "admin-own", true);
        assertQuantity(asset, warehouse, "1"); assertQuantity(asset, otherWarehouse, "0");
    }

    @Test void quantityModeDoesNotRequireSerialsInventoryCardsOrQualityDocuments() {
        asset.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED); asset.setIncomingInspectionRequired(true);
        materials.saveAndFlush(asset);
        service.record(request(asset, "3", "100"), null, "serial-receipt", true);
        service.record(request(asset, "1", null), null, "serial-issue", false);
        assertQuantity(asset, warehouse, "2");
        assertThat(jdbc.queryForObject("select count(*) from material_units where company_id=?", Long.class, company)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from quality_inspections where company_id=?", Long.class, company)).isZero();
    }

    @Test void insufficientIssueAndInvalidSecondReceiptLineRollbackEverything() {
        service.record(request(asset, "2", "100"), null, "seed", true);
        assertThatThrownBy(() -> service.record(request(asset, "3", null), null, "too-much", false)).isInstanceOf(RuntimeException.class);
        var second = material("ASSET-2", "Second asset", MaterialType.FIXED_ASSET);
        var invalid = new FixedAssetStockFactRequest(LocalDate.now(), null, null, List.of(
            new FixedAssetStockFactRequest.Line(asset.getId(), BigDecimal.ONE, BigDecimal.TEN, null),
            new FixedAssetStockFactRequest.Line(second.getId(), BigDecimal.ONE, BigDecimal.ZERO, null)));
        // The second line fails during posting (zero-cost policy), after the first line was posted.
        assertThatThrownBy(() -> service.record(invalid, null, "rollback", true)).isInstanceOf(RuntimeException.class);
        assertQuantity(asset, warehouse, "2"); assertQuantity(second, warehouse, "0");
        assertThat(jdbc.queryForObject("select count(*) from stock_movement_documents where company_id=?", Long.class, company)).isEqualTo(1);
    }

    @Test void wrongTypeFractionAndDuplicateLinesAreRejected() {
        assertThatThrownBy(() -> service.record(request(tmb, "1", "100"), null, "tmb", true)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.record(request(asset, "1.5", "100"), null, "fraction", true)).isInstanceOf(RuntimeException.class);
        var line = request(asset, "1", "100").lines().getFirst();
        assertThatThrownBy(() -> service.record(new FixedAssetStockFactRequest(LocalDate.now(), null, null, List.of(line, line)), null, "duplicate", true))
            .isInstanceOf(RuntimeException.class);
        assertQuantity(asset, warehouse, "0");
    }

    @Test void existingMaterialReceiptAndIssueStillWorkAndRejectFixedAssets() {
        var line = new WarehouseStockFactRequest.Line(tmb.getId(), new BigDecimal("5"), BigDecimal.TEN, null, null, null);
        materialFacts.record(new WarehouseStockFactRequest(warehouse, LocalDate.now(), null, null, List.of(line)), null, "tmb-receipt", true);
        var issue = new WarehouseStockFactRequest.Line(tmb.getId(), new BigDecimal("2"), null, null, null, null, PickingMethod.FIFO, StockAllocationMode.AUTO, null);
        materialFacts.record(new WarehouseStockFactRequest(warehouse, LocalDate.now(), null, null, List.of(issue)), null, "tmb-issue", false);
        assertQuantity(tmb, warehouse, "3");
        var wrong = new WarehouseStockFactRequest.Line(asset.getId(), BigDecimal.ONE, BigDecimal.TEN, null, null, null);
        assertThatThrownBy(() -> materialFacts.record(new WarehouseStockFactRequest(warehouse, LocalDate.now(), null, null, List.of(wrong)), null, "wrong-type", true))
            .isInstanceOf(RuntimeException.class);
        var raw = new StockMovement(); raw.setMaterialId(asset.getId()); raw.setWarehouseId(warehouse);
        raw.setMovementType(MovementType.RECEIPT); raw.setQuantity(BigDecimal.ONE); raw.setUnitCost(BigDecimal.TEN);
        assertThatThrownBy(() -> stock.createAndPost(raw)).isInstanceOf(RuntimeException.class);
    }

    @Test void concurrentIssuesCannotOverspendAndConcurrentRetriesPostOnce() throws Exception {
        service.record(request(asset, "5", "100"), null, "seed", true);
        assertThat(runTogether(false)).containsExactlyInAnyOrder(true, false);
        assertQuantity(asset, warehouse, "1");
        assertThat(runTogether(true)).containsExactly(true, true);
        assertQuantity(asset, warehouse, "3");
    }

    private List<Boolean> runTogether(boolean retry) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = retry ? "concurrent-retry" : "concurrent-issue-" + i;
                futures.add(pool.submit(() -> {
                    TenantContext.setTenant(tenant); CompanyContext.setCompany(company); authenticate(actor);
                    try { start.await(); service.record(request(asset, retry ? "2" : "4", retry ? "100" : null), null, key, retry); return true; }
                    catch (RuntimeException ex) { return false; }
                    finally { clear(); }
                }));
            }
            start.countDown();
            List<Boolean> result = new ArrayList<>();
            for (var future : futures) result.add(future.get(30, TimeUnit.SECONDS));
            return result;
        }
    }

    private FixedAssetStockFactRequest request(Material material, String quantity, String cost) {
        return new FixedAssetStockFactRequest(LocalDate.now(), null, null, List.of(new FixedAssetStockFactRequest.Line(
            material.getId(), new BigDecimal(quantity), cost == null ? null : new BigDecimal(cost), null)));
    }
    private void assertFixedAssetIssueError(String command, String language, String code, String message) throws Exception {
        String root = "/api/workspaces/warehouse-execution/manual/fixed-asset-stock";
        mvc.perform(post(root + "/issue-preview").accept("application/json").contentType("application/json").content(command)
            .with(user(actor).roles("WAREHOUSE_KEEPER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company).header("Accept-Language", language))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errorCode").value(code))
            .andExpect(jsonPath("$.message").value(message));
        mvc.perform(multipart(root + "/issue").file(jsonPart(command)).accept("application/json")
            .with(user(actor).roles("WAREHOUSE_KEEPER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company).header("Accept-Language", language)
            .header("Idempotency-Key", "localized-issue-" + UUID.randomUUID()))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errorCode").value(code))
            .andExpect(jsonPath("$.message").value(message));
    }
    private FixedAssetStockFactRequest manualRequest(String quantity, StockAllocationMode mode,
        WarehouseStockFactRequest.Allocation... allocations) {
        return new FixedAssetStockFactRequest(LocalDate.now(), null, null, List.of(
            new FixedAssetStockFactRequest.Line(asset.getId(), new BigDecimal(quantity), null, PickingMethod.FIFO, mode, List.of(allocations))));
    }
    private void assertPositionQuantity(UUID id, String expected) {
        assertThat(jdbc.queryForObject("select quantity from stock_balances where id=?", BigDecimal.class, id)).isEqualByComparingTo(expected);
    }
    private UUID warehouse(String code) {
        var w = new Warehouse(); w.setCode(code); w.setName("Warehouse " + code); w.setStatus("ACTIVE");
        return warehouses.saveAndFlush(w).getId();
    }
    private String employee(String code, UUID warehouseId) {
        var e = new Employee(); e.setCode(code); e.setName(code); e.setStatus("ACTIVE");
        e.setWarehouse(warehouses.findById(warehouseId).orElseThrow()); employees.saveAndFlush(e); return code;
    }
    private Material material(String code, String name, MaterialType type) {
        var m = new Material(); m.setCode(code); m.setName(name); m.setStatus("ACTIVE");
        m.setMaterialType(type); m.setUnitOfMeasureId(unit.getId()); return materials.saveAndFlush(m);
    }
    private void authenticate(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "unused",
            List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_KEEPER"))));
    }
    private void assertQuantity(Material material, UUID warehouseId, String expected) {
        BigDecimal value = jdbc.queryForObject("select coalesce(sum(quantity),0) from stock_balances where company_id=? and warehouse_id=? and material_id=? and is_delete=false",
            BigDecimal.class, company, warehouseId, material.getId());
        assertThat(value).isEqualByComparingTo(expected);
    }
}

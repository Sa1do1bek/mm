package com.enterprise.mm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.security.pilot.*;
import com.enterprise.mm.service.*;
import com.enterprise.mm.inventory.availability.service.InventoryAvailabilityWorkspaceService;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter;
import com.enterprise.mm.warehouse.execution.service.WarehouseExecutionWorkspaceService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = {"app.seed.enabled=false", "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
    "mm.pilot.enabled-capabilities=", "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers
@Transactional
class G2AccessCapabilityPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", PG::getJdbcUrl); p.add("spring.datasource.username", PG::getUsername);
        p.add("spring.datasource.password", PG::getPassword); p.add("spring.datasource.driver-class-name", PG::getDriverClassName);
    }
    @Autowired EntityManager em;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired IssueRequestService issues;
    @Autowired WarehouseExecutionWorkspaceService workspace;
    @Autowired InventoryAvailabilityWorkspaceService availability;
    @Autowired DemandPlanningCycleService cycles;
    @Autowired MrpService mrp;
    @Autowired PurchaseRequestRepository prs;
    @Autowired MaterialUnitEligibilityService units;
    @Autowired FixedAssetService assets;
    @Autowired AssetOpeningImportService assetImports;
    @Autowired ServiceRequestService services;
    @Autowired ContractInvoiceService invoices;
    @Autowired ContractPaymentService payments;
    @Autowired BudgetItemService budgets;
    @Autowired PilotCapabilityPolicy policy;
    @Autowired ImportService imports;
    @Autowired IntegrationDispatchService dispatch;
    @Autowired IntegrationSettingService integrations;
    @Autowired IntegrationMessageQueuePublisher publisher;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    Department maintenance, production;
    Warehouse main, mro;
    Material material;
    User requesterA, requesterB, mainWorker, mroWorker;
    final PageRequest page = PageRequest.of(0, 30);

    @BeforeEach void fixtures() {
        TenantContext.setTenant("default"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        maintenance = persist(base(new Department(), "Maintenance")); production = persist(base(new Department(), "Production"));
        main = persist(base(new Warehouse(), "Main")); mro = persist(base(new Warehouse(), "MRO"));
        material = base(new Material(), "Bolt"); material.setMaterialType(MaterialType.TMB); material = persist(material);
        requesterA = actor("maintenance", maintenance, null); requesterB = actor("production", production, null);
        mainWorker = actor("main", maintenance, main); mroWorker = actor("mro", production, mro);
        stock(main, "11"); stock(mro, "23");
        em.flush();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void departmentRequesterCreatesEditsSubmitsAndCannotSelfApprove(boolean second) {
        User actor = second ? requesterB : requesterA; Department dept = second ? production : maintenance;
        auth(actor, "DEPARTMENT_WORKER");
        assertThat(issues.permissions().get("create")).isTrue();
        var created = issues.create(request(actor, dept, main));
        assertThat(created.getAllowedActions()).contains("edit", "submit").doesNotContain("issue", "approve");
        em.flush();
        var update = json.convertValue(request(actor, dept, main), IssueRequestUpdateRequest.class);
        update.setExpectedVersion(issues.get(created.getId()).getVersion()); update.setPurpose("Corrected task");
        var edited = issues.update(created.getId(), update);
        assertThat(edited.getPurpose()).isEqualTo("Corrected task");
        assertThat(issues.submit(created.getId()).getStatus()).isEqualTo("SUBMITTED");
        auth(actor, "DEPARTMENT_HEAD");
        assertThatThrownBy(() -> issues.approve(created.getId(), null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void departmentListSearchDetailAndWorkspaceNeverExposeForeignRequest() {
        auth(requesterA, "DEPARTMENT_WORKER"); var a = issues.create(request(requesterA, maintenance, main));
        auth(requesterB, "DEPARTMENT_WORKER"); var b = issues.create(request(requesterB, production, main)); em.flush();
        for (User actor : List.of(requesterA, requesterB)) {
            auth(actor, "DEPARTMENT_WORKER"); UUID own = actor == requesterA ? a.getId() : b.getId(); UUID foreign = actor == requesterA ? b.getId() : a.getId();
            assertThat(issues.search(IssueRequestSearchRequest.empty(), page).getContent()).extracting(IssueRequestResponse::getId).containsExactly(own);
            assertThatThrownBy(() -> issues.get(foreign)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> workspace.issueRequestContext(foreign)).isInstanceOf(NotFoundException.class);
            assertThat(workspace.costObjectSelector("DEPARTMENT", null, page).getTotalElements()).isEqualTo(1);
            assertThat(workspace.userSelector(null, page).getContent()).allSatisfy(option -> assertThat(option.id()).isNotEqualTo(actor == requesterA ? requesterB.getId() : requesterA.getId()));
        }
    }

    @Test void independentDepartmentHeadCanApproveAndRequesterCanCorrectRejection() {
        auth(requesterA, "DEPARTMENT_WORKER"); var request = issues.create(request(requesterA, maintenance, main)); issues.submit(request.getId());
        User approver = actor("approver", maintenance, null); auth(approver, "DEPARTMENT_HEAD");
        assertThat(issues.get(request.getId()).getAllowedActions()).contains("approve"); issues.reject(request.getId(), null);
        auth(requesterA, "DEPARTMENT_WORKER"); em.flush();
        var update = json.convertValue(request(requesterA, maintenance, main), IssueRequestUpdateRequest.class);
        update.setExpectedVersion(issues.get(request.getId()).getVersion()); issues.update(request.getId(), update); issues.submit(request.getId());
        auth(approver, "DEPARTMENT_HEAD"); assertThat(issues.approve(request.getId(), null).getStatus()).isEqualTo("APPROVED");
    }

    @Test void creatorCannotImpersonateForeignRequesterOrDepartment() {
        auth(requesterA, "DEPARTMENT_WORKER");
        assertThatThrownBy(() -> issues.create(request(requesterB, production, main))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void directCostObjectCannotBypassDepartmentSelector() {
        auth(requesterA, "DEPARTMENT_WORKER");
        for (var type : List.of(IssueRequestCostObjectType.PROJECT, IssueRequestCostObjectType.REPAIR_ORDER)) {
            var body = request(requesterA, maintenance, main); body.setCostObjectType(type); body.setCostObjectId(UUID.randomUUID());
            assertThatThrownBy(() -> issues.create(body)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(workspace.costObjectSelector(type.name(), null, page).getContent()).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eligibleDepartmentSeesOnlyCollectingCurrentCompanyCycle(boolean second) {
        var open = cycle("COLLECTING", CompanyContext.currentCompany());
        var draft = cycle("DRAFT", CompanyContext.currentCompany());
        var foreign = cycle("COLLECTING", UUID.randomUUID()); em.flush();
        auth(second ? requesterB : requesterA, "DEPARTMENT_WORKER");
        assertThat(cycles.search(null, page).getContent()).extracting(DemandPlanningCycleResponse::getId).containsExactly(open.getId());
        assertThat(cycles.get(open.getId()).getId()).isEqualTo(open.getId());
        assertThatThrownBy(() -> cycles.get(draft.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> cycles.get(foreign.getId())).isInstanceOf(NotFoundException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void warehouseOperatorReadsOnlyAssignmentAndHasNoRequesterAuthority(boolean second) {
        auth(second ? mroWorker : mainWorker, "WAREHOUSE_OPERATOR");
        assertThat(availability.overview(filter(null)).totalOnHand()).isEqualByComparingTo(second ? "23" : "11");
        assertThat(availability.positions(filter(null), page).getContent()).hasSize(1);
        assertThatThrownBy(() -> availability.overview(filter(second ? main.getId() : mro.getId()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(issues.permissions().get("create")).isFalse();
        assertThatThrownBy(() -> issues.create(request(second ? mroWorker : mainWorker, second ? production : maintenance, main))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void revokedWarehouseAssignmentImmediatelyStopsAvailabilityAndForeignExecution() {
        auth(requesterA, "DEPARTMENT_WORKER"); var foreign = issues.create(request(requesterA, maintenance, mro));
        auth(mainWorker, "WAREHOUSE_OPERATOR");
        assertThatThrownBy(() -> issues.reserve(foreign.getId(), null)).isInstanceOf(NotFoundException.class);
        mainWorker.getEmployee().setWarehouse(null); em.flush();
        assertThatThrownBy(() -> availability.overview(filter(null))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void departmentCannotReadUnrestrictedAvailabilityAndCompanyRemainsIndependent() {
        auth(requesterA, "DEPARTMENT_WORKER"); var own = issues.create(request(requesterA, maintenance, main));
        assertThatThrownBy(() -> availability.overview(filter(null))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(() -> issues.get(own.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test void adminAndAuditorRetainLegitimateWiderReads() {
        auth(requesterA, "DEPARTMENT_WORKER"); issues.create(request(requesterA, maintenance, main));
        auth(requesterB, "DEPARTMENT_WORKER"); issues.create(request(requesterB, production, mro)); em.flush();
        for (String role : List.of("ADMIN", "AUDITOR")) {
            auth(mainWorker, role); assertThat(issues.search(IssueRequestSearchRequest.empty(), page).getTotalElements()).isEqualTo(2);
            assertThat(availability.overview(filter(null)).totalOnHand()).isEqualByComparingTo("34");
        }
    }

    @Test void mrpDirectServiceAndScheduleCannotCreateSupply() throws Exception {
        auth(mainWorker, "ADMIN"); long before = prs.count();
        assertThatThrownBy(mrp::runAndCreatePurchaseRequests).isInstanceOf(BusinessException.class).hasMessageContaining("MRP_GENERATION");
        assertThatThrownBy(mrp::runAndCreatePurchaseRequestsForAllTenants).isInstanceOf(BusinessException.class);
        mrp.scheduledRun(); assertThat(prs.count()).isEqualTo(before);
        mvc.perform(post("/api/mrp/run").with(user(mainWorker.getUsername()).roles("ADMIN")))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.capability").value("MRP_GENERATION"));
    }

    @Test void serialApplicationMutationDeniedButHistoryReadAvailable() throws Exception {
        auth(mainWorker, "ADMIN");
        assertThatThrownBy(() -> units.dispatchTransfer(material.getId(), main.getId(), null, null, BigDecimal.ONE,
            List.of(UUID.randomUUID()), "TransferDocument", UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(BusinessException.class).hasMessageContaining("SERIALIZED_EXECUTION");
        mvc.perform(get("/api/material-units").with(user(mainWorker.getUsername()).roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test void serialHttpMutationFailsBeforeCreatingBusinessState() throws Exception {
        auth(mainWorker, "ADMIN"); material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED); em.flush();
        var body = new StockMovementCreateRequest(); body.setName("Excluded serial movement");
        body.setMaterialId(material.getId()); body.setWarehouseId(main.getId());
        body.setMovementType(MovementType.RECEIPT); body.setQuantity(BigDecimal.ONE); body.setUnitCost(BigDecimal.TEN);
        for (int attempt = 0; attempt < 2; attempt++) {
            body.setName("Excluded serial movement " + attempt);
            mvc.perform(post("/api/stock/movements").with(user(mainWorker.getUsername()).roles("ADMIN"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.capability").value("SERIALIZED_EXECUTION"));
        }
    }

    @Test void assetHttpMutationFailsWithDifferentKeysAndVersions() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/asset-acceptances/" + UUID.randomUUID() + "/cancel")
                .with(user(mainWorker.getUsername()).roles("ADMIN"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"expectedVersion\":" + attempt + "}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.capability").value("FIXED_ASSET_EXECUTION"));
        }
    }

    @Test void serialHistoryUsesAssignedWarehouseBoundary() throws Exception {
        // Historical rows predate the deployment gate. SQL is test setup only, never live provisioning.
        for (Warehouse warehouse : List.of(main, mro)) {
            jdbc.update("""
                insert into material_units(id,tenant_id,company_id,name,status,created_at,version,is_delete,
                    material_id,manufacturer_serial_number,normalized_serial_number,unit_status,warehouse_id,
                    goods_receipt_line_id,goods_receipt_unit_id,received_at)
                values (?, 'default', ?, 'Historical serial', 'ACTIVE', now(), 0, false, ?, ?, ?, 'IN_STOCK', ?, ?, ?, now())
                """, UUID.randomUUID(), CompanyContext.currentCompany(), material.getId(), warehouse.getName(), warehouse.getName(),
                warehouse.getId(), UUID.randomUUID(), UUID.randomUUID());
        }
        mvc.perform(get("/api/material-units").with(user(mainWorker.getUsername()).roles("WAREHOUSE_OPERATOR")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].warehouseId").value(main.getId().toString()));
        mvc.perform(get("/api/material-units").param("warehouseId", mro.getId().toString())
            .with(user(mainWorker.getUsername()).roles("WAREHOUSE_OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/material-units").with(user(requesterA.getUsername()).roles("DEPARTMENT_WORKER")))
            .andExpect(status().isForbidden());
    }

    @Test void financeCommandsDeniedWithoutDisablingCountFinanceRole() throws Exception {
        auth(mainWorker, "ADMIN", "FINANCE_CONTROLLER");
        assertThatThrownBy(() -> invoices.issue(UUID.randomUUID())).isInstanceOf(BusinessException.class).hasMessageContaining("MM_FINANCE_EXECUTION");
        assertThatThrownBy(() -> payments.submitToTreasury(UUID.randomUUID(), null)).isInstanceOf(BusinessException.class).hasMessageContaining("MM_FINANCE_EXECUTION");
        assertThatThrownBy(() -> budgets.consume(UUID.randomUUID(), BigDecimal.ONE, "direct")).isInstanceOf(BusinessException.class).hasMessageContaining("MM_FINANCE_EXECUTION");
        mvc.perform(post("/api/contract-invoices/" + UUID.randomUUID() + "/issue").with(user(mainWorker.getUsername()).roles("ADMIN")))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.capability").value("MM_FINANCE_EXECUTION"));
    }

    @Test void assetAndServiceDirectBulkAndImportCommandsDenied() throws Exception {
        auth(mainWorker, "ADMIN");
        assertThatThrownBy(() -> assets.bulkCommission(null)).isInstanceOf(BusinessException.class).hasMessageContaining("FIXED_ASSET_EXECUTION");
        assertThatThrownBy(() -> assetImports.post(UUID.randomUUID(), null)).isInstanceOf(BusinessException.class).hasMessageContaining("FIXED_ASSET_EXECUTION");
        assertThatThrownBy(() -> services.create(new ServiceRequestCreateRequest())).isInstanceOf(BusinessException.class).hasMessageContaining("SERVICE_EXECUTION");
        mvc.perform(post("/api/service-requests").with(user(mainWorker.getUsername()).roles("ADMIN")).contentType("application/json").content("{\"name\":\"Pilot denied\"}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.capability").value("SERVICE_EXECUTION"));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value = MaterialType.class, names = {"TMB", "FIXED_ASSET", "SERVICES"})
    void excludedBalanceImportsFailThroughPolicyOrExistingTmbBoundary(MaterialType type) throws Exception {
        auth(mainWorker, "ADMIN"); material.setMaterialType(type);
        if (type == MaterialType.TMB) material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        String expectedDenial = "Direct balance overwrite is disabled";
        em.flush();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("stock balances"); var header = sheet.createRow(0);
            for (int i = 0; i < 4; i++) header.createCell(i).setCellValue(List.of("materialId", "warehouseId", "quantity", "totalValue").get(i));
            var row = sheet.createRow(1); row.createCell(0).setCellValue(material.getId().toString()); row.createCell(1).setCellValue(main.getId().toString()); row.createCell(2).setCellValue(7); row.createCell(3).setCellValue(700);
            workbook.write(out);
            assertThatThrownBy(() -> { imports.importStockBalances(new org.springframework.mock.web.MockMultipartFile("file", "opening.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray())); em.flush(); })
                .isInstanceOf(BusinessException.class).hasMessageContaining(expectedDenial);
        }
    }

    @Test void financeReplayAndProviderEntryAreBlockedBeforeTransport() {
        auth(mainWorker, "ADMIN");
        var event = base(new IntegrationEvent(), "historical-payment"); event.setEventType("TREASURY.PAYMENT_REQUEST"); event.setAggregateType("TREASURY");
        assertThatThrownBy(() -> publisher.publish(event, "{}")).isInstanceOf(BusinessException.class).hasMessageContaining("MM_FINANCE_EXECUTION");
        assertThatThrownBy(() -> integrations.enqueueAction(ExternalIntegrationSystem.TREASURY, "PAYMENT_REQUEST", null, UUID.randomUUID().toString()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("MM_FINANCE_EXECUTION");
    }

    @Test void policyStateIsObservableAndRoleCannotOverrideIt() throws Exception {
        assertThat(policy.enabledCapabilities()).isEmpty();
        mvc.perform(get("/api/pilot-capabilities").with(user(mainWorker.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabledCapabilities").isEmpty())
            .andExpect(jsonPath("$.data.disabledCapabilities.length()").value(5));
    }

    private IssueRequestCreateRequest request(User actor, Department department, Warehouse warehouse) {
        var r = new IssueRequestCreateRequest(); r.setName("Repair materials"); r.setWarehouseId(warehouse.getId());
        r.setRequesterUserId(actor.getId()); r.setReceiverUserId(actor.getId()); r.setCostObjectType(IssueRequestCostObjectType.DEPARTMENT); r.setCostObjectId(department.getId()); r.setRequiredDate(LocalDate.now());
        var l = new IssueRequestCreateRequest.Line(); l.setMaterialId(material.getId()); l.setRequestedQuantity(BigDecimal.ONE); r.setLines(List.of(l)); return r;
    }
    private DemandPlanningCycle cycle(String status, UUID company) {
        var c = base(new DemandPlanningCycle(), "Cycle"); c.setCompanyId(company); c.setStatus(status); c.setPlanYear(2027); c.setPeriodType("YEAR"); c.setPeriodStart(LocalDate.of(2027,1,1)); c.setPeriodEnd(LocalDate.of(2027,12,31)); return persist(c);
    }
    private InventoryAvailabilityFilter filter(UUID warehouse) { return new InventoryAvailabilityFilter(null, null, warehouse, null, null, null, null, null, null); }
    private User actor(String name, Department department, Warehouse warehouse) {
        var e = base(new Employee(), name); e.setDepartment(department); e.setWarehouse(warehouse); persist(e);
        var u = base(new User(), name); u.setUsername(name + UUID.randomUUID().toString().substring(0,8)); u.setFullName(name); u.setPasswordHash("test-only"); u.setDepartment(department); u.setEmployee(e); return persist(u);
    }
    private void stock(Warehouse warehouse, String amount) {
        var s = base(new StockBalance(), "Stock"); s.setWarehouseId(warehouse.getId()); s.setMaterialId(material.getId()); s.setQuantity(new BigDecimal(amount)); s.setAvailableQuantity(new BigDecimal(amount)); s.setReservedQuantity(BigDecimal.ZERO); s.setQuarantineQuantity(BigDecimal.ZERO); s.setTotalValue(new BigDecimal(amount).multiply(BigDecimal.TEN)); persist(s);
    }
    private <T extends BusinessEntity> T base(T entity, String name) { entity.setTenantId(TenantContext.currentTenant()); entity.setCompanyId(CompanyContext.currentCompany()); entity.setCode("G2-"+UUID.randomUUID().toString().substring(0,12)); entity.setName(name); entity.setStatus("ACTIVE"); return entity; }
    private <T> T persist(T entity) { em.persist(entity); return entity; }
    private void auth(User actor, String... roles) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.getUsername(), null, Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_"+r)).toList())); }
}

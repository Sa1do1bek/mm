package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** Real HTTP, approval engine, JPA and numbering; all records live only in the test database. */
@SpringBootTest(properties = {"app.seed.enabled=false", "app.features.fixed-assets.enabled=true", "mm.pilot.enabled-capabilities=FIXED_ASSET_EXECUTION"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ManualEquipmentReceiptIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    String tenant;
    UUID company, warehouse, material, approver, otherWarehouse;

    @BeforeEach void fixture() {
        tenant = "manual-e2e-" + UUID.randomUUID();
        TenantContext.setTenant(tenant);
        tx.executeWithoutResult(status -> {
            Company c = base(new Company(), "COMPANY"); em.persist(c); company = c.getId(); c.setCompanyId(company);
            CompanyContext.setCompany(company);
            Warehouse w = base(new Warehouse(), "070.1 склад"); em.persist(w); warehouse = w.getId();
            Warehouse other = base(new Warehouse(), "071 склад"); em.persist(other); otherWarehouse = other.getId();
            Warehouse inactive = base(new Warehouse(), "Inactive"); inactive.setStatus("INACTIVE"); em.persist(inactive);
            Warehouse foreign = base(new Warehouse(), "Foreign"); foreign.setCompanyId(UUID.randomUUID()); em.persist(foreign);
            Warehouse deleted = base(new Warehouse(), "Deleted"); deleted.softDelete(); em.persist(deleted);
            Employee e = base(new Employee(), "Warehouse keeper"); e.setWarehouse(w); em.persist(e);
            User wm = base(new User(), "Warehouse user"); wm.setUsername("warehouse.e2e"); wm.setPasswordHash("unused-test-only"); wm.setEmployee(e);
            wm.setRoles(Set.of(RoleName.WAREHOUSE_MANAGER)); em.persist(wm);
            User admin = base(new User(), "Asset approver"); admin.setUsername("approver.e2e"); admin.setPasswordHash("unused-test-only"); admin.setRoles(Set.of(RoleName.ADMIN)); em.persist(admin); approver = admin.getId();
            UnitOfMeasure u = base(new UnitOfMeasure(), "EA"); u.setStockQuantityScale(0); em.persist(u);
            Material m = base(new Material(), "Test equipment"); m.setMaterialType(MaterialType.FIXED_ASSET); m.setUnitOfMeasureId(u.getId()); em.persist(m); material = m.getId();
            AssetManagementSettings settings = base(new AssetManagementSettings(), "Settings"); settings.setCentralWarehouseId(warehouse); em.persist(settings);
            ApprovalFlow f = base(new ApprovalFlow(), "Asset approval"); f.setModuleKey("ASSET_ACCEPTANCE"); f.setActive(true); f.setFlowType(ApprovalFlowType.PARALLEL_ALL); em.persist(f);
            ApprovalFlowParallelAssignee a = base(new ApprovalFlowParallelAssignee(), "Approver"); a.setApprovalFlowId(f.getId()); a.setAssigneeUserId(approver); a.setAssigneeUsername("approver.e2e"); em.persist(a);
        });
        TenantContext.clear(); CompanyContext.clear();
    }
    <T extends BusinessEntity> T base(T e, String name) {
        e.setTenantId(tenant); e.setCompanyId(company == null ? UUID.randomUUID() : company); e.setName(name); e.setCode("T-" + UUID.randomUUID()); e.setStatus("ACTIVE");
        e.setCreatedAt(java.time.LocalDateTime.now()); return e;
    }
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, boolean admin) {
        return r.with(user(admin ? "approver.e2e" : "warehouse.e2e").roles(admin ? "ADMIN" : "WAREHOUSE_MANAGER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company).param("companyScope", "CURRENT_COMPANY");
    }
    JsonNode send(MockHttpServletRequestBuilder r, Object body, boolean admin, int expected) throws Exception {
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var response = mvc.perform(as(r, admin)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return json.readTree(response.getContentAsString()).path("data");
    }
    JsonNode getReceipt(String id) throws Exception { return send(get("/api/asset-acceptances/" + id), null, false, 200); }
    Map<String,Object> version(JsonNode receipt) { return Map.of("expectedVersion", receipt.path("version").asLong(), "reason", "Isolated manual equipment lifecycle test"); }

    @Test void warehouseReceiptRequiresQualityAndIndependentApprovalThenPostsExactlyOnce() throws Exception {
        var command = Map.of("warehouseId", warehouse, "materialId", material, "quantity", "1", "unitCost", "1200", "currencyCode", "UZS", "serialNumbers", List.of("TEST-E2E-1"));
        JsonNode receipt = send(post("/api/workspaces/warehouse-execution/manual/asset-receipt").header("Idempotency-Key", "create"), command, false, 200);
        String id = receipt.path("id").asText();
        assertThat(receipt.path("purchaseOrderId").isNull()).isTrue();
        assertThat(receipt.path("approvalStatus").asText()).isEqualTo("DRAFT");
        assertThat(receipt.path("postingStatus").asText()).isEqualTo("NOT_POSTED");
        assertThat(assetCount()).isZero();
        JsonNode pending = send(get("/api/workspaces/warehouse-execution/manual/asset-receipts").param("warehouseId", warehouse.toString()), null, false, 200);
        assertThat(pending.path("content").get(0).path("id").asText()).isEqualTo(id);
        send(get("/api/workspaces/warehouse-execution/manual/asset-receipts").param("warehouseId", otherWarehouse.toString()), null, false, 404);
        send(post("/api/asset-acceptances/" + id + "/submit"), version(receipt), false, 422);
        send(post("/api/asset-acceptances/" + id + "/post"), version(receipt), false, 422);
        String unit = receipt.path("lines").get(0).path("units").get(0).path("id").asText();
        receipt = send(post("/api/asset-acceptances/" + id + "/quality-decisions"), Map.of("expectedVersion", receipt.path("version").asLong(), "units", List.of(Map.of("assetAcceptanceUnitId", unit, "qualityDecision", "ACCEPTED", "reason", "Test inspection"))), false, 200);
        receipt = send(post("/api/asset-acceptances/" + id + "/submit"), version(receipt), false, 200);
        assertThat(receipt.path("approvalStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(assetCount()).isZero();
        JsonNode tasks = send(get("/api/approval-tasks/my").param("moduleKey", "ASSET_ACCEPTANCE"), null, true, 200);
        String task = tasks.path("content").get(0).path("id").asText();
        send(post("/api/approval-tasks/" + task + "/approve"), Map.of("comment", "Cannot self-approve"), false, 422);
        send(post("/api/approval-tasks/" + task + "/approve"), Map.of("comment", "Approved in isolated test"), true, 200);
        receipt = getReceipt(id);
        assertThat(receipt.path("approvalStatus").asText()).isEqualTo("APPROVED");
        assertThat(assetCount()).isZero();
        var postCommand = version(receipt);
        receipt = send(post("/api/asset-acceptances/" + id + "/post").header("Idempotency-Key", "post"), postCommand, false, 200);
        assertThat(receipt.path("postingStatus").asText()).isEqualTo("POSTED");
        assertThat(assetCount()).isEqualTo(1);
        pending = send(get("/api/workspaces/warehouse-execution/manual/asset-receipts").param("warehouseId", warehouse.toString()), null, false, 200);
        assertThat(pending.path("totalElements").asLong()).isZero();
        send(post("/api/asset-acceptances/" + id + "/post").header("Idempotency-Key", "post"), postCommand, false, 200);
        assertThat(assetCount()).isEqualTo(1);
        tx.executeWithoutResult(status -> {
            FixedAsset asset = em.createQuery("select a from FixedAsset a where a.tenantId=:t", FixedAsset.class).setParameter("t", tenant).getSingleResult();
            assertThat(asset.getWarehouseId()).isEqualTo(warehouse);
            assertThat(asset.getInventoryNumber()).startsWith("FA");
            assertThat(asset.getAcquisitionValue()).isEqualByComparingTo("1200");
        });
    }
    @Test void counterpartNamesExcludeOtherCompaniesInactiveDeletedAndOwnWarehouse() throws Exception {
        JsonNode options = send(get("/api/workspaces/warehouse-execution/manual/counterpart-warehouses").param("warehouseId", warehouse.toString()), null, false, 200);
        assertThat(options.path("content").size()).isEqualTo(1);
        assertThat(options.path("content").get(0).path("id").asText()).isEqualTo(otherWarehouse.toString());
        assertThat(options.path("content").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("id", "code", "name");
        send(get("/api/workspaces/warehouse-execution/manual/counterpart-warehouses").param("warehouseId", otherWarehouse.toString()), null, false, 404);
    }
    @Test void movementSearchWithoutImportedHistoryFindsMaterialAndSourceEvidence() throws Exception {
        final String[] materialCode = {null};
        tx.executeWithoutResult(status -> {
            materialCode[0] = em.find(Material.class, material).getCode();
            StockMovement movement = base(new StockMovement(), "Manual movement");
            movement.setMaterialId(material); movement.setWarehouseId(warehouse);
            movement.setMovementType(MovementType.RECEIPT); movement.setQuantity(java.math.BigDecimal.ONE);
            movement.setUnitCost(java.math.BigDecimal.ONE); movement.setTotalCost(java.math.BigDecimal.ONE);
            movement.setMovementDate(java.time.LocalDate.now()); movement.setStatus("POSTED");
            movement.setDescription("Workshop return NOTE-SEARCH"); em.persist(movement);
        });
        for (String search : List.of("Test equipment", materialCode[0], "Workshop return", "NOTE-SEARCH")) {
            JsonNode result = send(get("/api/stock/movements").param("search", search).param("warehouseId", warehouse.toString()), null, false, 200);
            assertThat(result.path("totalElements").asLong()).as(search).isEqualTo(1);
        }
    }
    @Test void stockBalanceResponseExplicitlyIdentifiesCompanyOwnedOperationalStock() throws Exception {
        tx.executeWithoutResult(status -> {
            StockBalance balance = base(new StockBalance(), "Owned operational stock");
            balance.setMaterialId(material); balance.setWarehouseId(warehouse);
            balance.setQuantity(new java.math.BigDecimal("2")); balance.setAvailableQuantity(new java.math.BigDecimal("2"));
            balance.setTotalValue(new java.math.BigDecimal("2")); em.persist(balance);
        });
        JsonNode result = send(get("/api/stock/balances").param("warehouseId", warehouse.toString()).param("materialId", material.toString()), null, false, 200);
        JsonNode position = result.path("content").get(0);
        assertThat(position.path("stockCategory").asText()).isEqualTo("OWNED");
        assertThat(position.has("ownerPartyId")).isTrue();
        assertThat(position.path("ownerPartyId").isNull()).isTrue();
        assertThat(new java.math.BigDecimal(position.path("quantity").asText())).isEqualByComparingTo("2");
    }
    long assetCount() { return tx.execute(status -> em.createQuery("select count(a) from FixedAsset a where a.tenantId=:t", Long.class).setParameter("t", tenant).getSingleResult()); }
}

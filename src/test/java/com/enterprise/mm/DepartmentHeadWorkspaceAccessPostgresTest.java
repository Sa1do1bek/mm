package com.enterprise.mm;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real HTTP and PostgreSQL coverage for a head with two department warehouse grants. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.seed.enabled=false", "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
    "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false", "app.scheduling.enabled=false"
})
@EntityScan(basePackages={"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity", "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
@ActiveProfiles("test")
@Testcontainers
class DepartmentHeadWorkspaceAccessPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired CompanyRepository companies;
    @Autowired DepartmentRepository departments;
    @Autowired DepartmentWarehouseAccessRepository access;
    @Autowired WarehouseRepository warehouses;
    @Autowired EmployeeRepository employees;
    @Autowired UserRepository users;
    @Autowired MaterialGroupRepository groups;
    @Autowired UnitOfMeasureRepository units;
    @Autowired MaterialRepository materials;
    @Autowired StockBatchRepository batches;
    @Autowired StockBalanceRepository balances;
    @Autowired StorageLocationRepository locations;
    @Autowired StorageBinRepository bins;
    @Autowired FixedAssetRepository assets;
    @Autowired CommissionRepository commissions;
    @Autowired CommissionMemberRepository members;
    @Autowired InventoryDocumentRepository inventories;
    @Autowired PurchaseRequestRepository requests;
    @Autowired PurchaseOrderRepository orders;
    @Autowired PurchaseOrderItemRepository orderItems;
    @Autowired SupplierRepository suppliers;
    @Autowired DemandPlanRepository plans;
    @Autowired IssueRequestRepository issues;
    @Autowired JwtService jwt;
    @Autowired ContractRepository contracts;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    UUID companyId;
    private <T extends BusinessEntity> T named(T entity, String code, String name, String status) {
        entity.setTenantId(TenantContext.DEFAULT_TENANT); entity.setCompanyId(companyId);
        entity.setCode(code); entity.setName(name); entity.setStatus(status); return entity;
    }
    @Test void departmentHeadCanUseWarehousePagesWithoutCrossDepartmentStockAccess() throws Exception {
        TenantContext.setTenant(TenantContext.DEFAULT_TENANT);
        Company company = companies.saveAndFlush(named(new Company(), "AUDIT", "Audit industrial company", "ACTIVE"));
        companyId=company.getId(); CompanyContext.setCompany(companyId);
        Department own = departments.saveAndFlush(named(new Department(), "MAINT", "Maintenance department", "ACTIVE"));
        Department foreign = departments.saveAndFlush(named(new Department(), "FOREIGN", "Other department", "ACTIVE"));
        Warehouse first = warehouses.saveAndFlush(named(new Warehouse(), "WH-1", "Main department warehouse", "ACTIVE"));
        Warehouse second = warehouses.saveAndFlush(named(new Warehouse(), "WH-2", "Workshop department warehouse", "ACTIVE"));
        Warehouse outside = warehouses.saveAndFlush(named(new Warehouse(), "WH-X", "Other department warehouse", "ACTIVE"));
        for (Warehouse w : List.of(first,second)) {
            var grant=new DepartmentWarehouseAccess(); grant.setTenantId(TenantContext.DEFAULT_TENANT); grant.setCompanyId(companyId);
            grant.setDepartment(own); grant.setWarehouse(w); access.saveAndFlush(grant);
        }
        Employee employee= named(new Employee(), "HEAD-EMP", "Department Head", "ACTIVE"); employee.setDepartment(own); employee.setWarehouse(first);
        employees.saveAndFlush(employee);
        User actor=named(new User(), "HEAD", "Department Head", "ACTIVE"); actor.setUsername("head.audit"); actor.setFullName("Department Head");
        actor.setPasswordHash("test-only-not-login"); actor.setEnabled(true); actor.setRoles(Set.of(RoleName.DEPARTMENT_HEAD)); actor.setDepartment(own); actor.setEmployee(employee); users.saveAndFlush(actor);
        var unit=units.saveAndFlush(named(new UnitOfMeasure(),"pcs","Pieces","ACTIVE"));
        var group=groups.saveAndFlush(named(new MaterialGroup(),"PARTS","Maintenance parts","ACTIVE"));
        var material=named(new Material(),"MAT-001","Pump bearing","ACTIVE"); material.setMaterialGroupId(group.getId()); material.setUnitOfMeasureId(unit.getId()); materials.saveAndFlush(material);
        var fixed=named(new Material(),"ASSET-001","Workshop pump","ACTIVE"); fixed.setMaterialType(MaterialType.FIXED_ASSET); fixed.setMaterialGroupId(group.getId()); fixed.setUnitOfMeasureId(unit.getId()); materials.saveAndFlush(fixed);
        for (Warehouse w : List.of(first,second,outside)) {
            var location=named(new StorageLocation(),"LOC-"+w.getCode(),"Main storage area","ACTIVE"); location.setWarehouseId(w.getId()); locations.saveAndFlush(location);
            var bin=named(new StorageBin(),"BIN-"+w.getCode(),"Shelf A","ACTIVE"); bin.setStorageLocationId(location.getId()); bin.setWarehouseId(w.getId()); bins.saveAndFlush(bin);
            var batch=named(new StockBatch(),"BATCH-"+w.getCode(),"Opening batch","ACTIVE"); batch.setMaterialId(material.getId()); batch.setWarehouseId(w.getId()); batch.setBatchNumber("B-001"); batch.setQuantity(BigDecimal.TEN); batch.setUnitCost(BigDecimal.TEN); batch.setReceivedDate(LocalDate.now()); batches.saveAndFlush(batch);
            var balance=named(new StockBalance(),"BAL-"+w.getCode(),"Opening stock","ACTIVE"); balance.setMaterialId(material.getId()); balance.setWarehouseId(w.getId()); balance.setStockBatchId(batch.getId()); balance.setStorageLocationId(location.getId()); balance.setStorageBinId(bin.getId()); balance.setBalanceAt(LocalDateTime.now()); balance.setQuantity(BigDecimal.TEN); balance.setAvailableQuantity(BigDecimal.TEN); balance.setTotalValue(new BigDecimal("100")); balances.saveAndFlush(balance);
        }
        var asset=named(new FixedAsset(),"FA-001","Workshop pump asset","ACTIVE"); asset.setMaterialId(fixed.getId()); asset.setWarehouseId(first.getId()); asset.setInventoryNumber("FA-0001"); assets.saveAndFlush(asset);
        var commission=commissions.saveAndFlush(named(new Commission(),"COM-1","Department inventory commission","ACTIVE"));
        var member=named(new CommissionMember(),"MEM-1","Department Head","ACTIVE"); member.setCommissionId(commission.getId()); member.setUserId(actor.getId()); member.setMemberName("Department Head"); member.setMemberRole(CommissionMemberRole.CHAIRMAN); members.saveAndFlush(member);
        var inventory=named(new InventoryDocument(),"INV-AUDIT","Department stocktake","DRAFT"); inventory.setWarehouseId(first.getId()); inventory.setCommissionId(commission.getId()); inventory.setResponsibleUserId(actor.getId()); inventories.saveAndFlush(inventory);
        var supplier=suppliers.saveAndFlush(named(new Supplier(),"SUP-1","Bearing supplier","ACTIVE"));
        var request=named(new PurchaseRequest(),"PR-1","Department bearings","DRAFT"); request.setDepartment(own); request.setRequestedBy(actor.getUsername()); request.setWarehouseId(first.getId()); requests.saveAndFlush(request);
        var order=named(new PurchaseOrder(),"PO-1","Bearing delivery","APPROVED"); order.setSupplier(supplier); order.setPurchaseRequest(request); order.setOrderDate(LocalDate.now()); orders.saveAndFlush(order);
        var orderLine=named(new PurchaseOrderItem(),"PO-LINE-1","Bearing delivery line","ACTIVE"); orderLine.setPurchaseOrderId(order.getId()); orderLine.setMaterialId(material.getId()); orderLine.setMaterialTypeSnapshot(material.getMaterialType()); orderLine.setTrackingModeSnapshot(material.getTrackingMode()); orderLine.setBatchTrackingSnapshot(material.getBatchTrackingPolicy()); orderLine.setSerialTrackingSnapshot(material.getSerialTrackingPolicy()); orderLine.setQuantity(new BigDecimal("5")); orderLine.setUnitPrice(BigDecimal.TEN); orderLine.setReceiptWarehouseId(first.getId()); orderItems.saveAndFlush(orderLine);
        var sharedRequest=named(new PurchaseRequest(),"PR-SHARED","Another department's materials","APPROVED"); sharedRequest.setDepartment(foreign); sharedRequest.setRequestedBy("another.head"); sharedRequest.setWarehouseId(first.getId()); requests.saveAndFlush(sharedRequest);
        var sharedOrder=named(new PurchaseOrder(),"PO-SHARED","Delivery to our warehouse for another department","APPROVED"); sharedOrder.setSupplier(supplier); sharedOrder.setPurchaseRequest(sharedRequest); sharedOrder.setOrderDate(LocalDate.now()); orders.saveAndFlush(sharedOrder);
        var sharedLine=named(new PurchaseOrderItem(),"PO-LINE-SHARED","Shared receiving line","ACTIVE"); sharedLine.setPurchaseOrderId(sharedOrder.getId()); sharedLine.setMaterialId(material.getId()); sharedLine.setMaterialTypeSnapshot(material.getMaterialType()); sharedLine.setTrackingModeSnapshot(material.getTrackingMode()); sharedLine.setBatchTrackingSnapshot(material.getBatchTrackingPolicy()); sharedLine.setSerialTrackingSnapshot(material.getSerialTrackingPolicy()); sharedLine.setQuantity(new BigDecimal("5")); sharedLine.setUnitPrice(BigDecimal.TEN); sharedLine.setReceiptWarehouseId(first.getId()); orderItems.saveAndFlush(sharedLine);
        var plan=named(new DemandPlan(),"DP-1","Department annual demand","DRAFT"); plan.setDepartment(own); plan.setPlanYear(LocalDate.now().getYear()); plan.setPeriodType("YEAR"); plan.setPeriodStart(LocalDate.now().withDayOfYear(1)); plan.setPeriodEnd(LocalDate.now().withMonth(12).withDayOfMonth(31)); plans.saveAndFlush(plan);
        var issue=named(new IssueRequest(),"IR-1","Bearing issue request","DRAFT"); issue.setWarehouseId(first.getId()); issue.setRequesterUserId(actor.getId()); issue.setReceiverUserId(actor.getId()); issue.setCostObjectType(IssueRequestCostObjectType.DEPARTMENT); issue.setCostObjectId(own.getId()); issues.saveAndFlush(issue);
        var contract=named(new Contract(),"CON-1","Maintenance supply contract","ACTIVE"); contract.setSupplierId(supplier.getId()); contract.setSupplier(supplier); contract.setContractNumber("MAINT-2026"); contracts.saveAndFlush(contract);
        UUID accountImport = UUID.randomUUID();
        jdbc.update("insert into warehouse_account_imports(id,tenant_id,company_id,organization_source_id,snapshot_date,manifest_sha256,expected_counts,expected_records,accepted_records,expected_chain,chain_sha256,status,completed_at) values(?,?,?,?,?,?,'{}'::jsonb,6,6,?,?,'IMPORTING',null)", accountImport,TenantContext.DEFAULT_TENANT,companyId,"audit",LocalDate.now(),"a".repeat(64),"a".repeat(64),"a".repeat(64));
        int sequence=0;
        for (Warehouse w : List.of(first,second,outside)) {
            for (Material m : List.of(material,fixed)) {
                sequence++;
                jdbc.update("""
                    insert into warehouse_account_entries(id,tenant_id,company_id,import_id,source_seq,source_key,kind,source_date,account_source_id,account_code,account_name,material_reference_type,material_source_id,warehouse_source_id,material_id,warehouse_id,material_code,material_name,warehouse_code,warehouse_name,source_type,source_material_type,stock_category,uom,uom_name_ru,quantity,amount,additional_dimensions,row_sha256,content)
                    values(?,?,?,?,?,?,'BALANCE',?,'audit','1010','Materials','00000036',?,?,?,?,?,?,?,?,?,?,'OWNED','pcs','Pieces',10,100,'{}'::jsonb,?,'{}')
                    """,UUID.randomUUID(),TenantContext.DEFAULT_TENANT,companyId,accountImport,sequence,"audit-"+sequence,LocalDate.now(),m.getId().toString().replace("-",""),w.getId().toString().replace("-",""),m.getId(),w.getId(),m.getCode(),m.getName(),w.getCode(),w.getName(),m==fixed?"FIXED_ASSET":"MATERIAL",m==fixed?"Fixed assets":"Materials","a".repeat(64));
            }
        }
        jdbc.update("update warehouse_account_imports set status='COMPLETED', completed_at=current_timestamp where id=?",accountImport);
        Map<String,Object> session=new LinkedHashMap<>(); session.put("backendUrl","http://127.0.0.1:"+port); session.put("token", jwt.generate(actor,List.of(new SimpleGrantedAuthority("ROLE_DEPARTMENT_HEAD")))); session.put("username",actor.getUsername()); session.put("companyId",companyId); session.put("companyName",company.getName()); session.put("departmentId",own.getId()); session.put("warehouseIds",List.of(first.getId(),second.getId())); session.put("outsideWarehouseId",outside.getId()); session.put("inventoryId",inventory.getId()); session.put("requestId",request.getId()); session.put("orderId",order.getId()); session.put("planId",plan.getId()); session.put("issueId",issue.getId()); session.put("assetId",asset.getId()); session.put("materialId",material.getId()); session.put("fixedMaterialId",fixed.getId()); session.put("contractId",contract.getId()); session.put("sharedOrderId",sharedOrder.getId());
        verifyWorkspace(session);
    }

    @org.junit.jupiter.api.AfterEach void clearContext() { TenantContext.clear(); CompanyContext.clear(); }
    private JsonNode get(Map<String,Object> session, String path, int expected) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(session.get("backendUrl")+path)).timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer "+session.get("token"))
            .header("X-Tenant-Id", TenantContext.DEFAULT_TENANT).header("X-Company-Id",companyId.toString())
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s",path,response.body()).isEqualTo(expected);
        return json.readTree(response.body()).path("data");
    }
    private void verifyWorkspace(Map<String,Object> session) throws Exception {
        String first = ((List<?>) session.get("warehouseIds")).getFirst().toString();
        String second = ((List<?>) session.get("warehouseIds")).getLast().toString();
        String outside = session.get("outsideWarehouseId").toString();
        String material = session.get("materialId").toString();
        String day = LocalDate.now().toString();
        var profile = get(session,"/api/auth/me",200);
        assertThat(profile.path("assignedWarehouseId").isNull()).isTrue();
        assertThat(profile.path("accessibleWarehouseIds").toString()).contains(first,second).doesNotContain(outside);
        for (String path : List.of("/api/stock/balances", "/api/stock/batches", "/api/storage-locations", "/api/storage-bins")) {
            var rows = get(session,path,200);
            assertThat(rows.path("totalElements").asLong()).as(path).isEqualTo(2);
            assertThat(rows.path("content").toString()).contains(first,second).doesNotContain(outside);
            assertThat(get(session,path+"?warehouseId="+second,200).path("totalElements").asLong()).as(path).isEqualTo(1);
        }
        var balances = get(session,"/api/workspaces/inventory-availability/overview",200);
        assertThat(balances.path("totalOnHand").decimalValue()).isEqualByComparingTo("20");
        get(session,"/api/warehouse-reports/options",200);
        get(session,"/api/workspaces/services-acceptance/intake/options",200);
        var report=get(session,"/api/warehouse-reports?type=STOCK_BALANCES&from="+day+"&to="+day,200);
        assertThat(report.toString()).contains(first,second).doesNotContain(outside);
        var card=get(session,"/api/warehouse-reports/material-card?materialId="+material+"&from="+day+"&to="+day,200);
        assertThat(new BigDecimal(card.path("closingQuantity").asText())).isEqualByComparingTo("20");
        var contracts=get(session,"/api/workspaces/warehouse-execution/selectors/contracts",200);
        assertThat(contracts.toString()).contains("MAINT-2026").doesNotContain("totalAmount","paymentTerms","balance");
        get(session,"/api/contracts",403);
        get(session,"/api/purchase-orders/"+session.get("sharedOrderId"),200);
        get(session,"/api/purchase-orders/"+session.get("sharedOrderId")+"/workspace",200);
        get(session,"/api/workspaces/goods-income/purchase-orders/"+session.get("sharedOrderId"),200);
        get(session,"/api/inventory/"+session.get("inventoryId"),200);
        for (String warehouse : List.of(first,second)) {
            var summary=get(session,"/api/materials/"+material+"/command-view?warehouseId="+warehouse,200);
            assertThat(new BigDecimal(summary.path("inventory").path("onHand").path("value").asText())).isEqualByComparingTo("10");
            get(session,"/api/materials/"+material+"/command-view/availability?warehouseId="+warehouse,200);
        }
        get(session,"/api/materials/"+material+"/command-view?warehouseId="+outside,403);
        get(session,"/api/stock/balances?warehouseId="+outside,403);
        get(session,"/api/warehouse-reports?type=STOCK_BALANCES&from="+day+"&to="+day+"&warehouseId="+outside,403);
    }

}

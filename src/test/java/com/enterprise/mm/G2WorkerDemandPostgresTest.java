package com.enterprise.mm;
import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.service.*;
import com.enterprise.mm.service.approval.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.payload.ApprovalDtos.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.transaction.TestTransaction;
import com.enterprise.mm.enums.*;
import java.util.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
@org.springframework.boot.test.context.SpringBootTest(properties = {"app.seed.enabled=false", "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "mm.pilot.enabled-capabilities=", "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false"})
@org.springframework.test.context.ActiveProfiles("test")
@org.springframework.boot.autoconfigure.domain.EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@org.testcontainers.junit.jupiter.Testcontainers
@org.springframework.transaction.annotation.Transactional
class G2WorkerDemandPostgresTest {
 @org.testcontainers.junit.jupiter.Container static final org.testcontainers.containers.PostgreSQLContainer<?> PG = new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");
 @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry p) {
  p.add("spring.datasource.url",PG::getJdbcUrl);p.add("spring.datasource.username",PG::getUsername);p.add("spring.datasource.password",PG::getPassword);p.add("spring.datasource.driver-class-name",PG::getDriverClassName);
 }
 @Autowired jakarta.persistence.EntityManager em;
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Autowired IssueRequestService issues;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
 Department maintenance, production; Warehouse main; Material material; User requesterA, mainWorker, departmentHead, buyer;
 <T extends BusinessEntity> T fixture(T value,String name) { value.setName(name);value.setCode("G2-"+UUID.randomUUID());value.setStatus("ACTIVE");em.persist(value);return value; }
 User actor(String name,Department department,Warehouse warehouse,RoleName role) {
  var employee=new Employee();employee.setDepartment(department);employee.setWarehouse(warehouse);fixture(employee,name);
  var user=new User();user.setUsername(name.toLowerCase()+UUID.randomUUID().toString().substring(0,8));user.setFullName(name);user.setPasswordHash("test-only");user.setDepartment(department);user.setEmployee(employee);user.setRoles(new HashSet<>(Set.of(role)));return fixture(user,name);
 }
 @org.junit.jupiter.api.AfterEach void cleanup() { SecurityContextHolder.clearContext();TenantContext.clear();CompanyContext.clear(); }

 @Test void rejectsUnrelatedDemandSourceBeforeIssueMutation() {
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(requesterA.getUsername(),null,List.of(new SimpleGrantedAuthority("ROLE_DEPARTMENT_WORKER"))));
  var plan=new com.enterprise.mm.entity.DemandPlan();plan.setName("Production plan");plan.setCode("FOREIGN-"+UUID.randomUUID());plan.setDepartment(production);plan.setPlanYear(LocalDate.now().getYear());plan.setPeriodType("YEAR");plan.setPeriodStart(LocalDate.now().withDayOfYear(1));plan.setPeriodEnd(LocalDate.now().withMonth(12).withDayOfMonth(31));plan.setStatus("APPROVED");em.persist(plan);
  var source=new com.enterprise.mm.entity.DemandPlanItem();source.setName("Foreign demand");source.setCode("FOREIGN-ITEM-"+UUID.randomUUID());source.setDemandPlanId(plan.getId());source.setDepartmentId(production.getId());source.setMaterialId(material.getId());source.setMaterialTypeSnapshot(material.getMaterialType());source.setRequestedQuantity(new BigDecimal("5"));source.setApprovedQuantity(new BigDecimal("3"));source.setDemandType("OPERATIONS");source.setPriority("NORMAL");source.setStatus("APPROVED");em.persist(source);em.flush();
  var body=new IssueRequestCreateRequest();body.setName("Linked maintenance need");body.setRequesterUserId(requesterA.getId());body.setReceiverUserId(requesterA.getId());body.setWarehouseId(main.getId());body.setCostObjectType(IssueRequestCostObjectType.DEPARTMENT);body.setCostObjectId(maintenance.getId());body.setRequiredDate(LocalDate.now());
  var line=new IssueRequestCreateRequest.Line();line.setMaterialId(material.getId());line.setRequestedQuantity(BigDecimal.ONE);line.setSourceDemandPlanItemId(source.getId());body.setLines(List.of(line));
  assertThatThrownBy(()->issues.create(body)).isInstanceOf(RuntimeException.class).hasMessageContaining("demand");
 }

 @Autowired StockBalanceService stockBalances;
 @Autowired DemandPlanService plans;
 @Autowired DemandPlanItemService demandItems;
 @Autowired ApprovalEngineService approval;
 @Autowired StockMovementService movements;
 @Autowired PurchaseRequestItemRepository prItems;
 @Autowired DemandPlanItemRepository demands;
 @Autowired DemandPlanWorkspaceService demandWorkspace;
 @BeforeEach void stockUnit() {
  TenantContext.setTenant("default");CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
  maintenance=fixture(new Department(),"Maintenance");production=fixture(new Department(),"Production");main=fixture(new Warehouse(),"Main");
  material=new Material();material.setMaterialType(MaterialType.TMB);fixture(material,"MAT-X Bolt");
  requesterA=actor("Aziza",maintenance,null,RoleName.DEPARTMENT_WORKER);mainWorker=actor("Otabek",maintenance,main,RoleName.WAREHOUSE_OPERATOR);
  departmentHead=actor("Dilshod",maintenance,null,RoleName.DEPARTMENT_HEAD);buyer=actor("Nodira",maintenance,null,RoleName.PROCUREMENT_SPECIALIST);
  var unit=new UnitOfMeasure();unit.setCode("EA-"+UUID.randomUUID());unit.setName("Each");unit.setStatus("ACTIVE");unit.setMeasurementNameShort("EA");unit.setUnitType(MeasurementUnitType.QUANTITY);em.persist(unit);
  var flow=new ApprovalFlow();flow.setName("Stock approval");flow.setCode("STOCK-"+UUID.randomUUID());flow.setModuleKey("STOCK_MOVEMENT");flow.setActive(true);flow.setStatus("ACTIVE");em.persist(flow);
  var step=new ApprovalFlowStep();step.setName("Warehouse review");step.setCode("S-"+UUID.randomUUID());step.setApprovalFlowId(flow.getId());step.setStepOrder(1);step.setAssigneeType(ApprovalAssigneeType.ROLE);step.setAssigneeRole("WAREHOUSE_MANAGER");em.persist(step);
  material.setUnitOfMeasure(unit);material.setUnitOfMeasureId(unit.getId());em.flush();
 }
 void as(User user,String role) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getUsername(),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)))); }
 DemandPlanItem demand(String need) {
  as(requesterA,"DEPARTMENT_WORKER");
  var plan=new DemandPlan();plan.setName("Maintenance pilot demand");plan.setCode("DP-"+UUID.randomUUID());plan.setDepartment(maintenance);plan.setPlanYear(LocalDate.now().getYear());plan.setPeriodType("YEAR");plan.setPeriodStart(LocalDate.now().withDayOfYear(1));plan.setPeriodEnd(LocalDate.now().withMonth(12).withDayOfMonth(31));plan.setOwnerUsername(requesterA.getUsername());plan.setStatus("DRAFT");em.persist(plan);
  var item=new DemandPlanItem();item.setName("Bolts for maintenance");item.setCode("DI-"+UUID.randomUUID());item.setDemandPlan(plan);item.setDemandPlanId(plan.getId());item.setDepartment(maintenance);item.setDepartmentId(maintenance.getId());item.setMaterial(material);item.setMaterialId(material.getId());item.setMaterialTypeSnapshot(MaterialType.TMB);item.setRequestedQuantity(new BigDecimal(need));item.setDemandType("OPERATIONS");item.setPriority("NORMAL");item.setStatus("DRAFT");em.persist(item);em.flush();
  var flow=new ApprovalFlow();flow.setCode("FLOW-"+UUID.randomUUID());flow.setName("Independent pilot approval");flow.setStatus("ACTIVE");flow.setModuleKey("DEMAND_PLAN");flow.setDepartmentId(maintenance.getId());flow.setRequestType(ApprovalRequestType.GOODS);em.persist(flow);
  for(int order=1;order<=2;order++) { var step=new ApprovalFlowStep();step.setCode("STEP-"+UUID.randomUUID());step.setName(order==1?"Check usable stock":"Independent department approval");step.setApprovalFlowId(flow.getId());step.setStepOrder(order);step.setStepType(order==1?ApprovalStepType.DEMAND_STOCK_CHECK:ApprovalStepType.STANDARD);step.setWarehouseId(main.getId());step.setAssigneeType(ApprovalAssigneeType.USER);step.setAssigneeUserId(order==1?mainWorker.getId():departmentHead.getId());step.setCanReturn(true);em.persist(step); }
  em.flush();return item;
 }
 void review(DemandPlanItem item,String stock,String buy,boolean finish) {
  as(requesterA,"DEPARTMENT_WORKER");plans.submit(item.getDemandPlanId());
  as(mainWorker,"WAREHOUSE_OPERATOR");var task=approval.myTasks("PENDING").stream().filter(t->t.entityId().equals(item.getDemandPlanId())).findFirst().orElseThrow();
  var state=approval.approveTask(task.id(),new ApprovalTaskDecisionRequest("Stock reviewed",null,List.of(new DemandStockReviewLineDecision(item.getId(),new BigDecimal(buy),"Use existing stock",new BigDecimal(stock)))));
  if(finish) { as(departmentHead,"DEPARTMENT_HEAD");approval.approveTask(state.tasks().stream().filter(t->t.taskStatus()==ApprovalTaskStatus.PENDING).findFirst().orElseThrow().id(),"Independent approval"); }
  em.flush();
 }
 IssueRequestCreateRequest linked(DemandPlanItem source,String quantity) {
  var body=new IssueRequestCreateRequest();body.setName("Maintenance stock fulfillment");body.setRequesterUserId(requesterA.getId());body.setReceiverUserId(requesterA.getId());body.setWarehouseId(main.getId());body.setCostObjectType(IssueRequestCostObjectType.DEPARTMENT);body.setCostObjectId(maintenance.getId());body.setRequiredDate(LocalDate.now());
  var line=new IssueRequestCreateRequest.Line();line.setMaterialId(material.getId());line.setRequestedQuantity(new BigDecimal(quantity));line.setSourceDemandPlanItemId(source.getId());body.setLines(List.of(line));return body;
 }
 UUID convert(DemandPlanItem item,String quantity) {
  as(buyer,"PROCUREMENT_SPECIALIST");var first=plans.createPurchaseRequests(item.getDemandPlanId());var repeat=plans.createPurchaseRequests(item.getDemandPlanId());assertThat(repeat.purchaseRequestIds()).isEqualTo(first.purchaseRequestIds());
  if(new BigDecimal(quantity).signum()==0) { assertThat(first.purchaseRequestIds()).isEmpty();return null; }
  assertThat(prItems.findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(TenantContext.currentTenant(),first.purchaseRequestIds().getFirst())).singleElement().satisfies(i->assertThat(i.getQuantity()).isEqualByComparingTo(quantity));
  assertThat(jdbc.queryForObject("select count(*) from demand_plan_item_pr_allocations where demand_plan_item_id=? and is_delete=false",Integer.class,item.getId())).isEqualTo(1);
  return first.purchaseRequestIds().getFirst();
 }
 StockMovementResponse receipt(String amount,String batchName) {
  as(mainWorker,"WAREHOUSE_OPERATOR");var location=new StorageLocation();location.setName("Rack "+batchName);location.setCode("LOC-"+UUID.randomUUID());location.setWarehouse(main);location.setWarehouseId(main.getId());location.setStatus("ACTIVE");em.persist(location);
  var batch=new StockBatch();batch.setName(batchName);batch.setCode(batchName+UUID.randomUUID());batch.setBatchNumber(batchName);batch.setMaterial(material);batch.setWarehouse(main);batch.setMaterialId(material.getId());batch.setWarehouseId(main.getId());batch.setStatus("ACTIVE");em.persist(batch);em.flush();
  var bin=new StorageBin();bin.setName("Bin "+batchName);bin.setCode("BIN-"+UUID.randomUUID());bin.setBinCode(batchName);bin.setWarehouse(main);bin.setWarehouseId(main.getId());bin.setStorageLocation(location);bin.setStorageLocationId(location.getId());bin.setStatus("ACTIVE");em.persist(bin);em.flush();
  var body=new StockMovementCreateRequest();body.setStorageBinId(bin.getId());body.setName("Pilot receipt");body.setMaterialId(material.getId());body.setWarehouseId(main.getId());body.setStorageLocationId(location.getId());body.setStockBatchId(batch.getId());body.setMovementType(MovementType.RECEIPT);body.setQuantity(new BigDecimal(amount));body.setUnitCost(BigDecimal.TEN);body.setMovementDate(LocalDate.now());
  var movement=movements.create(body);var posted=movements.post(movement.getId());em.flush();em.clear();return posted;
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void canonicalPartialIssuesKeepProcurementThreeAndSourceThroughCorrection(boolean closeEarly) {
  var stock=receipt("2","B-01");var source=demand("5");review(source,"2","3",true);convert(source,"3");
  as(requesterA,"DEPARTMENT_WORKER");var created=issues.create(linked(source,"2"));issues.submit(created.getId());as(departmentHead,"DEPARTMENT_HEAD");issues.reject(created.getId(),new ServiceActionRequest("Clarify purpose",null,null));as(requesterA,"DEPARTMENT_WORKER");em.flush();
  var correction=json.convertValue(linked(source,"2"),IssueRequestUpdateRequest.class);correction.setExpectedVersion(issues.get(created.getId()).getVersion());var edited=issues.update(created.getId(),correction);assertThat(edited.getLines().getFirst().getSourceDemandPlanItemId()).isEqualTo(source.getId());issues.submit(created.getId());
  as(departmentHead,"DEPARTMENT_HEAD");issues.approve(created.getId(),null);
  as(mainWorker,"WAREHOUSE_OPERATOR");var reserve=new IssueRequestReserveRequest();reserve.setWarehouseId(main.getId());var rl=new IssueRequestReserveRequest.Line();rl.setIssueRequestLineId(edited.getLines().getFirst().getId());rl.setReservedQuantity(new BigDecimal("2"));rl.setStorageBinId(stock.getStorageBinId());rl.setStockBatchId(stock.getStockBatchId());rl.setStorageLocationId(stock.getStorageLocationId());reserve.setLines(List.of(rl));issues.reserve(created.getId(),reserve);em.flush();
  for(int n=1;n<=(closeEarly?1:2);n++) {
   var before=issues.get(created.getId());var cmd=new IssueRequestIssueRequest();cmd.setExpectedVersion(before.getVersion());cmd.setWarehouseId(main.getId());var line=new IssueRequestIssueRequest.Line();line.setIssueRequestLineId(before.getLines().getFirst().getId());line.setIssuedQuantity(BigDecimal.ONE);line.setStorageBinId(stock.getStorageBinId());line.setStockBatchId(stock.getStockBatchId());line.setStorageLocationId(stock.getStorageLocationId());cmd.setLines(List.of(line));
   var positions=issues.stockPositions(created.getId(),line.getIssueRequestLineId());assertThat(positions.stream().filter(p->stock.getStockBatchId().equals(p.getStockBatchId())).findFirst().orElseThrow().getExecutableQuantity()).isEqualByComparingTo(n==1?"2":"1");
   String key="partial-"+n+UUID.randomUUID();var result=issues.issue(created.getId(),cmd,key);em.flush();assertThat(issues.issue(created.getId(),cmd,key).getLines().getFirst().getIssuedQuantity()).isEqualByComparingTo(String.valueOf(n));
   assertThat(result.getLines().getFirst().getSourceDemandPlanItemId()).isEqualTo(source.getId());assertThat(source.getApprovedQuantity()).isEqualByComparingTo("3");
   em.flush();em.clear();var projection=demandWorkspace.detail(source.getDemandPlanId());assertThat(projection.items().getFirst().coverageStatus()).isEqualTo("PARTIAL");assertThat(projection.supplySummary().status()).isEqualTo("PARTIAL");
   if(n==1) assertThatThrownBy(()->issues.issue(created.getId(),cmd,"stale-"+UUID.randomUUID())).isInstanceOf(com.enterprise.mm.error.ConflictException.class);
  }
  as(departmentHead,"DEPARTMENT_HEAD");issues.close(created.getId(),new ServiceActionRequest("Fulfilled or approved short close",null,null));convert(source,"3");
  as(requesterA,"DEPARTMENT_WORKER");assertThat((BigDecimal)issues.demandSource(source.getId()).get("remainingQuantity")).isEqualByComparingTo(closeEarly?"1":"0");
  if(closeEarly) {assertThat(issues.create(linked(source,"1")).getLines().getFirst().getSourceDemandPlanItemId()).isEqualTo(source.getId());
   as(mainWorker,"WAREHOUSE_OPERATOR");assertThat(issues.stockPositions(created.getId(),edited.getLines().getFirst().getId()).getFirst().getAvailableQuantity()).isEqualByComparingTo("1");}
 }
 @Test void fullStockCreatesNoZeroPurchaseRequest() {
  var stock=receipt("5","FULL");var source=demand("5");review(source,"5","0",true);convert(source,"0");
  as(requesterA,"DEPARTMENT_WORKER");var request=issues.create(linked(source,"5"));issues.submit(request.getId());
  as(departmentHead,"DEPARTMENT_HEAD");issues.approve(request.getId(),null);
  as(mainWorker,"WAREHOUSE_OPERATOR");var command=new IssueRequestIssueRequest();command.setWarehouseId(main.getId());command.setExpectedVersion(issues.get(request.getId()).getVersion());
  var line=new IssueRequestIssueRequest.Line();line.setIssueRequestLineId(request.getLines().getFirst().getId());line.setIssuedQuantity(new BigDecimal("5"));line.setStockBatchId(stock.getStockBatchId());line.setStorageLocationId(stock.getStorageLocationId());line.setStorageBinId(stock.getStorageBinId());command.setLines(List.of(line));
  var issued=issues.issue(request.getId(),command,"full-stock-"+UUID.randomUUID());
  assertThat(issued.getLines().getFirst().getSourceDemandPlanItemId()).isEqualTo(source.getId());assertThat(issued.getLines().getFirst().getIssuedQuantity()).isEqualByComparingTo("5");
  convert(source,"0");as(requesterA,"DEPARTMENT_WORKER");assertThat((BigDecimal)issues.demandSource(source.getId()).get("remainingQuantity")).isZero();
  em.flush();em.clear();var projection=demandWorkspace.detail(source.getDemandPlanId());assertThat(projection.items().getFirst().coverageStatus()).isEqualTo("SUPPLIED");assertThat(projection.supplySummary().status()).isEqualTo("SUPPLIED");
 }
 @Test void noStockKeepsFullProcurementAndCannotClaimAnIssue() { var source=demand("5");review(source,"0","5",true);convert(source,"5");as(requesterA,"DEPARTMENT_WORKER");assertThatThrownBy(()->issues.create(linked(source,"1"))).hasMessageContaining("stock portion"); }
 @Test void duplicateClaimsMaterialAndCompanyInjectionDenied() {
  receipt("2","AVAILABLE");var source=demand("5");review(source,"2","3",true);as(requesterA,"DEPARTMENT_WORKER");issues.create(linked(source,"2"));assertThatThrownBy(()->issues.create(linked(source,"1"))).hasMessageContaining("stock portion");
  var other=new Material();other.setCode("OTHER-"+UUID.randomUUID());other.setName("Other material");other.setMaterialType(MaterialType.TMB);other.setStatus("ACTIVE");em.persist(other);source.setMaterial(null);source.setMaterialId(other.getId());assertThatThrownBy(()->issues.create(linked(source,"1"))).hasMessageContaining("material");source.setMaterialId(material.getId());
  var company=new Company();company.setName("Other company");company.setCode("OTHER-"+UUID.randomUUID());company.setStatus("ACTIVE");em.persist(company);source.setCompanyId(company.getId());assertThatThrownBy(()->issues.create(linked(source,"1"))).hasMessageContaining("company");
 }
 @Test void returnForCorrectionRequiresFreshStockApprovalAndBuysTwo() {
  receipt("2","CORRECTION");var source=demand("5");review(source,"2","3",false);as(departmentHead,"DEPARTMENT_HEAD");var task=approval.myTasks("PENDING").stream().filter(t->t.entityId().equals(source.getDemandPlanId())).findFirst().orElseThrow();approval.returnTask(task.id(),1,"Need corrected to four");
  as(requesterA,"DEPARTMENT_WORKER");assertThat(source.getStatus()).isEqualTo("DRAFT");assertThat(source.getStockFulfillmentQuantity()).isZero();
  var update=new DemandPlanItemUpdateRequest();update.setName(source.getName());update.setDemandPlanId(source.getDemandPlanId());update.setMaterialId(material.getId());update.setDepartmentId(maintenance.getId());update.setRequestedQuantity(new BigDecimal("4"));update.setDemandType("OPERATIONS");update.setPriority("NORMAL");demandItems.update(source.getId(),update);
  as(buyer,"PROCUREMENT_SPECIALIST");assertThatThrownBy(()->plans.createPurchaseRequests(source.getDemandPlanId())).hasMessageContaining("not allowed");review(source,"2","2",true);convert(source,"2");
 }
 @Test void concurrentConversionCreatesOneLogicalSourceAllocation() throws Exception {
  receipt("2","CONCURRENT");var source=demand("5");review(source,"2","3",true);UUID planId=source.getDemandPlanId();TestTransaction.flagForCommit();TestTransaction.end();
  var barrier=new java.util.concurrent.CyclicBarrier(2);
  try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   java.util.concurrent.Callable<UUID> work=()->{TenantContext.setTenant("default");CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);as(buyer,"PROCUREMENT_SPECIALIST");try {barrier.await();return plans.createPurchaseRequests(planId).purchaseRequestIds().getFirst();} finally {SecurityContextHolder.clearContext();TenantContext.clear();CompanyContext.clear();}};
   var a=executor.submit(work);var b=executor.submit(work);assertThat(a.get(30,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(30,java.util.concurrent.TimeUnit.SECONDS));
  }
  assertThat(jdbc.queryForObject("select count(*) from demand_plan_item_pr_allocations where demand_plan_item_id=? and is_delete=false",Integer.class,source.getId())).isEqualTo(1);
 }
 @Test void workerPositionsAndDirectCommandsRejectHeldExhaustedWrongLocationAndExpired() {
  var first=receipt("3","B-01");var second=receipt("7","B-02");var held=receipt("2","HOLD");var exhausted=receipt("1","EMPTY");
  em.createQuery("update StockBalance b set b.availableQuantity=0,b.quarantineQuantity=2 where b.stockBatchId=:id").setParameter("id",held.getStockBatchId()).executeUpdate();
  em.createQuery("update StockBalance b set b.availableQuantity=0,b.reservedQuantity=1 where b.stockBatchId=:id").setParameter("id",exhausted.getStockBatchId()).executeUpdate();em.clear();
  var source=demand("1");review(source,"1","0",true);as(requesterA,"DEPARTMENT_WORKER");var request=issues.create(linked(source,"1"));issues.submit(request.getId());as(departmentHead,"DEPARTMENT_HEAD");issues.approve(request.getId(),null);as(mainWorker,"WAREHOUSE_OPERATOR");
  var lineId=request.getLines().getFirst().getId();var positions=issues.stockPositions(request.getId(),lineId);
  assertThat(positions).hasSize(4);assertThat(positions.stream().filter(p->p.getStockBatchId().equals(first.getStockBatchId())).findFirst().orElseThrow().getAvailableQuantity()).isEqualByComparingTo("3");
  assertThat(positions.stream().filter(p->p.getStockBatchId().equals(second.getStockBatchId())).findFirst().orElseThrow().getAvailableQuantity()).isEqualByComparingTo("7");
  assertThat(positions.stream().filter(p->p.getStockBatchId().equals(held.getStockBatchId())).findFirst().orElseThrow().getUnavailableReason()).isEqualTo("hold");
  assertThat(positions.stream().filter(p->p.getStockBatchId().equals(exhausted.getStockBatchId())).findFirst().orElseThrow().getUnavailableReason()).isEqualTo("exhausted");
  for(var position:List.of(held,exhausted)) { var command=new IssueRequestReserveRequest();var line=new IssueRequestReserveRequest.Line();line.setIssueRequestLineId(lineId);line.setStorageBinId(position.getStorageBinId());line.setStockBatchId(position.getStockBatchId());line.setStorageLocationId(position.getStorageLocationId());line.setReservedQuantity(BigDecimal.ONE);command.setLines(List.of(line));assertThatThrownBy(()->issues.reserve(request.getId(),command)).isInstanceOf(com.enterprise.mm.error.InsufficientStockException.class); }
  var command=new IssueRequestReserveRequest();var line=new IssueRequestReserveRequest.Line();line.setIssueRequestLineId(lineId);line.setStockBatchId(first.getStockBatchId());line.setStorageLocationId(second.getStorageLocationId());line.setReservedQuantity(BigDecimal.ONE);command.setLines(List.of(line));assertThatThrownBy(()->issues.reserve(request.getId(),command)).isInstanceOf(com.enterprise.mm.error.InsufficientStockException.class);
  line.setStorageLocationId(first.getStorageLocationId());line.setStorageBinId(first.getStorageBinId());em.find(StockBatch.class,first.getStockBatchId()).setExpiresOn(LocalDate.now().minusDays(1));assertThatThrownBy(()->issues.reserve(request.getId(),command)).hasMessageContaining("unavailable");
  assertThat(issues.get(request.getId()).getStatus()).isEqualTo("APPROVED");
 }

 @Test void permissionFailureIsNotZeroAvailableStock() {
  var foreign=fixture(new Warehouse(),"Unassigned warehouse");as(mainWorker,"WAREHOUSE_OPERATOR");
  assertThatThrownBy(()->stockBalances.getWarehouseMaterialBalances(foreign.getId(),List.of(material.getId())))
   .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
 }

}

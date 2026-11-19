package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.payload.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.transaction.TestTransaction;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"app.seed.enabled=false","spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","mm.pilot.enabled-capabilities=","app.firebase.enabled=false","mm.integrations.cbu.enabled=false","app.cors.allowed-origins=http://127.0.0.1:5183"})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="mm.live.worker-demand",matches="true")
class G2LiveWorkerDemandRehearsalTest extends G2WorkerDemandPostgresTest {
 @org.springframework.boot.test.web.server.LocalServerPort int port;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
 @Test void liveWorkerDemand() throws Exception {
  String password="Pilot!"+UUID.randomUUID();
  var actors=new LinkedHashMap<String,Object>();
  for(var entry:Map.of("requester",requesterA,"warehouse",mainWorker,"head",departmentHead,"buyer",buyer).entrySet()) {
   var user=entry.getValue();user.setPasswordHash(encoder.encode(password));
   actors.put(entry.getKey(),Map.of("username",user.getUsername(),"name",user.getFullName(),"id",user.getId()));
  }
  material.getUnitOfMeasure().setCode("EA");
  material.setCode("MAT-X");em.merge(material);em.flush();
  var first=receipt("3","B-01");var second=receipt("7","B-02");var exhausted=receipt("1","B-EMPTY");var held=receipt("2","B-HOLD");
  var firstLocation=em.find(StorageLocation.class,first.getStorageLocationId());firstLocation.setName("Rack A-01");firstLocation.setCode("RACK-A01");
  var secondLocation=em.find(StorageLocation.class,second.getStorageLocationId());secondLocation.setName("Rack B-04");secondLocation.setCode("RACK-B04");
  em.find(StorageLocation.class,exhausted.getStorageLocationId()).setCode("RACK-EMPTY");em.find(StorageLocation.class,held.getStorageLocationId()).setCode("QUALITY-HOLD");
  // Fixture dispositions: physical held quantity remains valued; exhausted stock is posted out.
  var out=new StockMovementCreateRequest();out.setName("Exhaust fixture");out.setMaterialId(material.getId());out.setWarehouseId(main.getId());out.setStorageLocationId(exhausted.getStorageLocationId());out.setStorageBinId(exhausted.getStorageBinId());out.setStockBatchId(exhausted.getStockBatchId());out.setMovementType(MovementType.ISSUE);out.setQuantity(BigDecimal.ONE);out.setUnitCost(BigDecimal.TEN);movements.post(movements.create(out).getId());
  em.createQuery("update StockBalance b set b.availableQuantity=0,b.quarantineQuantity=2 where b.stockBatchId=:batch").setParameter("batch",held.getStockBatchId()).executeUpdate();em.flush();em.clear();
  as(requesterA,"DEPARTMENT_WORKER");var unlinked=new IssueRequestCreateRequest();unlinked.setName("Identify MAT-X stock");unlinked.setRequesterUserId(requesterA.getId());unlinked.setReceiverUserId(requesterA.getId());unlinked.setWarehouseId(main.getId());unlinked.setCostObjectType(IssueRequestCostObjectType.DEPARTMENT);unlinked.setCostObjectId(maintenance.getId());unlinked.setRequiredDate(java.time.LocalDate.now());var line=new IssueRequestCreateRequest.Line();line.setMaterialId(material.getId());line.setRequestedQuantity(BigDecimal.ONE);unlinked.setLines(List.of(line));var stockRequest=issues.create(unlinked);issues.submit(stockRequest.getId());as(departmentHead,"DEPARTMENT_HEAD");issues.approve(stockRequest.getId(),null);
  var fixture=new LinkedHashMap<String,Object>();fixture.put("api","http://127.0.0.1:"+port);fixture.put("company",com.enterprise.mm.common.CompanyContext.DEFAULT_COMPANY_ID);fixture.put("actors",actors);fixture.put("warehouseId",main.getId());fixture.put("stockRequestId",stockRequest.getId());fixture.put("b01",first);fixture.put("b02",second);fixture.put("exhausted",exhausted);fixture.put("held",held);
  var unit=em.find(Material.class,material.getId()).getUnitOfMeasure();material=new Material();material.setMaterialType(MaterialType.TMB);material.setUnitOfMeasure(unit);material.setUnitOfMeasureId(unit.getId());fixture(material,"Maintenance bolts");material.setCode("MAT-DEMAND-5");em.flush();
  var demandStock=receipt("2","D-02");em.find(StorageLocation.class,demandStock.getStorageLocationId()).setCode("RACK-D02");var source=demand("5");review(source,"2","3",true);
  fixture.put("demandPlanId",source.getDemandPlanId());fixture.put("demandItemId",source.getId());fixture.put("demandMaterialId",material.getId());fixture.put("demandStock",demandStock);
  em.flush();TestTransaction.flagForCommit();TestTransaction.end();
  var dir=Path.of("../docs/reports/g2-b07-b08-evidence").toAbsolutePath().normalize();Files.createDirectories(dir);json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("live-fixtures.json").toFile(),fixture);
  var builder=new ProcessBuilder("node",dir.resolve("live-rehearsal.cjs").toString());builder.environment().put("MM_LIVE_PASSWORD",password);builder.redirectErrorStream(true);builder.redirectOutput(dir.resolve("live-run.log").toFile());
  var process=builder.start();boolean finished=process.waitFor(8,java.util.concurrent.TimeUnit.MINUTES);if(!finished)process.destroyForcibly();assertThat(finished).isTrue();assertThat(process.exitValue()).as("See live-run.log").isZero();
  assertThat(jdbc.queryForObject("select sum(allocated_quantity) from demand_plan_item_pr_allocations where demand_plan_item_id=? and is_delete=false",BigDecimal.class,source.getId())).isEqualByComparingTo("3");
  assertThat(jdbc.queryForObject("select sum(issued_quantity) from issue_request_lines where source_demand_plan_item_id=? and is_delete=false",BigDecimal.class,source.getId())).isEqualByComparingTo("2");
 }
}

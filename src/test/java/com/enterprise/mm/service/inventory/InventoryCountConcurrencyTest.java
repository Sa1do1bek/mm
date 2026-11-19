package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.InventoryCountBatchDtos.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.InventoryDocumentService;
import com.enterprise.mm.service.approval.InventoryDocumentApprovalAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest @ActiveProfiles("test")
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers(disabledWithoutDocker=false)
@TestPropertySource(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","app.seed.enabled=false","mm.pilot.enabled-capabilities=","app.scheduling.enabled=false","app.firebase.enabled=false","mm.integrations.cbu.enabled=false"})
class InventoryCountConcurrencyTest {
    @Autowired UnitOfMeasureRepository b09Units;
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);r.add("spring.datasource.driver-class-name",DB::getDriverClassName);}
    @Autowired InventoryCountBatchService batches; @Autowired InventoryCountMutationService mutations;
    @Autowired InventoryDocumentRepository documents; @Autowired InventoryItemRepository items;
    @Autowired com.enterprise.mm.service.approval.ApprovalEngineService engine;
    @Autowired com.enterprise.mm.service.approval.ApprovalFlowAdminService flowAdmin;
    @Autowired InventoryDocumentService lifecycle; @Autowired InventoryDocumentApprovalAdapter approval;
    @Autowired WarehouseRepository warehouses; @Autowired MaterialRepository materials;
    @Autowired CommissionRepository commissions; @Autowired CommissionMemberRepository members; @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions; @Autowired ObjectMapper json;
    private InventoryDocument document; private InventoryItem first,second; private String counter; private UUID memberId;
    private ExecutorService executor;
    @BeforeEach void fixture() {
        authenticate("admin","ADMIN");com.enterprise.mm.B09InventoryMasterFixture.ensure(materials,b09Units,users);executor=Executors.newFixedThreadPool(2);String suffix=UUID.randomUUID().toString().substring(0,8);
        var warehouse=new Warehouse();warehouse.setName("Concurrent count "+suffix);warehouse=warehouses.saveAndFlush(warehouse);
        var material=materials.findAll().stream().filter(m->CompanyContext.currentCompany().equals(m.getCompanyId())&&!m.isDeleted()&&m.getMaterialType()==MaterialType.TMB).findFirst().orElseThrow();
        var commission=new Commission();commission.setName("Counters "+suffix);commission.setType(CommissionType.INVENTORY);commission.setActive(true);commission=commissions.saveAndFlush(commission);
        counter="counter-"+suffix;var user=new User();user.setUsername(counter);user.setName(counter);user.setPasswordHash("fixture-only");user=users.saveAndFlush(user);
        var member=new CommissionMember();member.setName(counter);member.setMemberName(counter);member.setCommissionId(commission.getId());member.setUserId(user.getId());member.setMemberRole(CommissionMemberRole.COUNTER);memberId=members.saveAndFlush(member).getId();
        document=new InventoryDocument();document.setName("Concurrent count "+suffix);document.setWarehouseId(warehouse.getId());document.setCommissionId(commission.getId());document.setStatus("IN_PROGRESS");document.setSnapshotAt(LocalDateTime.now());document.setTotalItems(2);document=documents.saveAndFlush(document);
        first=line(material,warehouse);second=line(materials.findAll().stream().filter(m->m.getMaterialType()==MaterialType.TMB && !m.getId().equals(material.getId())).findFirst().orElseThrow(),warehouse);
    }
    private InventoryItem line(Material material,Warehouse warehouse) {
        var item=new InventoryItem();item.setName("Observation");item.setInventoryDocumentId(document.getId());item.setMaterialId(material.getId());item.setWarehouseId(warehouse.getId());item.setSystemQuantity(new BigDecimal("100"));item.setUnitCost(new BigDecimal("15"));item.setStatus("NOT_COUNTED");return items.saveAndFlush(item);
    }
    private void authenticate(String username,String role){TenantContext.setTenant("default");CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username,"",List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    @AfterEach void clear(){executor.shutdownNow();SecurityContextHolder.clearContext();TenantContext.clear();CompanyContext.clear();}
    private RowCommand row(InventoryItem line,String value){return new RowCommand(line.getId(),line.getVersion(),value==null?null:new BigDecimal(value),value==null,null);}
    private BatchCommand command(RowCommand... rows){return new BatchCommand(doc().getVersion(),List.of(rows));}
    private InventoryDocument doc(){return documents.findById(document.getId()).orElseThrow();}
    private InventoryItem reload(InventoryItem line){return items.findById(line.getId()).orElseThrow();}
    private <T>Future<T> async(Supplier<T> work){return executor.submit(()->{authenticate("admin","ADMIN");try{return work.get();}finally{SecurityContextHolder.clearContext();TenantContext.clear();CompanyContext.clear();}});}
    private void await(CountDownLatch latch){try{assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @Test void successfulRowsSurviveInvalidAndOutOfDocumentRowsAndClearIsDistinctFromZero() {
        var missing=new RowCommand(UUID.randomUUID(),0L,BigDecimal.ONE,false,null);
        var result=batches.save(document.getId(),command(row(first,"0"),row(second,"-1"),missing),"partial");
        assertThat(result.rows()).extracting(RowResult::outcome).containsExactly("SAVED","INVALID","INVALID");
        assertThat(reload(first).getCountedQuantity()).isZero();assertThat(reload(second).getCountedQuantity()).isNull();assertThat(doc().getCountRevision()).isEqualTo(1);
        var zeroVersion=reload(first).getVersion();
        batches.save(document.getId(),command(row(reload(first),"0")),"unchanged");
        assertThat(doc().getCountRevision()).isEqualTo(1);assertThat(reload(first).getVersion()).isEqualTo(zeroVersion);
        batches.save(document.getId(),command(row(reload(first),null)),"clear");
        assertThat(reload(first).getCountedQuantity()).isNull();assertThat(reload(first).getLineStatus()).isEqualTo(InventoryLineStatus.NOT_COUNTED);assertThat(doc().getCountedItems()).isZero();assertThat(doc().getCountRevision()).isEqualTo(2);
    }
    @Test void responseLossAfterOneCommitResumesDurableAdmissionAndChangedPayloadIsRejected() {
        String key="lost-response";var command=command(row(first,"97"),row(second,"100"));
        mutations.admitBatch(document.getId(),command,key);
        mutations.saveOne(document.getId(),command.rows().getFirst(),key+":"+first.getId());
        // The client did not receive the row result; the document version has already advanced.
        assertThat(doc().getVersion()).isGreaterThan(command.expectedDocumentVersion());
        var recovered=batches.save(document.getId(),command,key);
        assertThat(recovered.rows()).extracting(RowResult::outcome).containsExactly("SAVED","SAVED");assertThat(doc().getCountRevision()).isEqualTo(2);
        var replay=batches.save(document.getId(),command,key);assertThat(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(replay)).isEqualTo(json.<com.fasterxml.jackson.databind.JsonNode>valueToTree(recovered));
        assertThatThrownBy(()->batches.save(document.getId(),new BatchCommand(command.expectedDocumentVersion(),List.of(row(first,"98"))),key)).isInstanceOf(ConflictException.class);
        assertThat(reload(first).getCountedQuantity()).isEqualByComparingTo("97");
    }
    @Test void twoCountersOfTheSameLineProduceOneSavedObservationAndOneConflict()throws Exception {
        var a=command(row(first,"97"));var b=command(row(first,"98"));mutations.admitBatch(document.getId(),a,"a");mutations.admitBatch(document.getId(),b,"b");
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        var x=async(()->{ready.countDown();await(go);return mutations.saveOne(document.getId(),a.rows().getFirst(),"a:"+first.getId());});
        var y=async(()->{ready.countDown();await(go);return mutations.saveOne(document.getId(),b.rows().getFirst(),"b:"+first.getId());});await(ready);go.countDown();
        var results=List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));assertThat(results).extracting(RowResult::outcome).containsExactlyInAnyOrder("SAVED","CONFLICT");
        var conflict=results.stream().filter(r->r.outcome().equals("CONFLICT")).findFirst().orElseThrow();assertThat(conflict.current().getCountedQuantity()).isEqualByComparingTo(reload(first).getCountedQuantity());assertThat(doc().getCountRevision()).isEqualTo(1);
    }
    @Test void differentLinesUseTheirOwnVersionsUnderTheSameAdmittedDocument()throws Exception {
        var command=command(row(first,"97"),row(second,"98"));mutations.admitBatch(document.getId(),command,"different");
        var a=async(()->mutations.saveOne(document.getId(),command.rows().get(0),"different:"+first.getId()));
        var b=async(()->mutations.saveOne(document.getId(),command.rows().get(1),"different:"+second.getId()));
        assertThat(a.get(15,TimeUnit.SECONDS).outcome()).isEqualTo("SAVED");assertThat(b.get(15,TimeUnit.SECONDS).outcome()).isEqualTo("SAVED");assertThat(doc().getCountRevision()).isEqualTo(2);
    }
    @Test void approvalSerializesWithLateSaveAndSavedReplayStillSucceeds()throws Exception {
        String key="approval-race";var original=command(row(first,"100"),row(second,"99"));mutations.admitBatch(document.getId(),original,key);
        mutations.saveOne(document.getId(),original.rows().getFirst(),key+":"+first.getId());
        batches.save(document.getId(),command(row(second,"100")),"other-counter");lifecycle.completeCounting(document.getId());
        var firstStep = new com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepRequest(null, "Commission review", 1, ApprovalStepType.STANDARD, null, null,
            ApprovalAssigneeType.ROLE, "MM_MANAGER", null, null, false, false, true, null);
        var lastStep = new com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepRequest(null, "Commission approval", 2, ApprovalStepType.STANDARD, null, null,
            ApprovalAssigneeType.ROLE, "MM_MANAGER", null, null, true, true, true, null);
        flowAdmin.create(new com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowRequest(CompanyContext.currentCompany(), null, "Inventory commission",
            "INVENTORY_DOCUMENT", ApprovalFlowType.SEQUENTIAL, null, null, true, "ACTIVE", null, List.of(firstStep, lastStep), List.of()));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var approving=async(()->new TransactionTemplate(transactions).execute(s->{engine.start("INVENTORY_DOCUMENT", document.getId());locked.countDown();await(release);return true;}));await(locked);
        var late=async(()->batches.save(document.getId(),original,key));Thread.sleep(150);assertThat(late.isDone()).isFalse();release.countDown();approving.get(10,TimeUnit.SECONDS);
        var result=late.get(10,TimeUnit.SECONDS);assertThat(result.rows()).extracting(RowResult::outcome).containsExactly("SAVED","NOT_EDITABLE");
        assertThat(reload(second).getCountedQuantity()).isEqualByComparingTo("100");assertThat(doc().getStatus()).isEqualTo("WAITING_APPROVAL");assertThat(doc().getSubmittedCountRevision()).isEqualTo(doc().getCountRevision());
    }
    @Test void cachedRowsAreReprojectedAndRevokedMembershipCannotReplay()throws Exception {
        var command=command(row(first,"97"));var saved=batches.save(document.getId(),command,"visible-first");assertThat(saved.rows().getFirst().saved().getSystemQuantity()).isNotNull();
        authenticate(counter,"DEPARTMENT_WORKER");var blind=batches.save(document.getId(),command,"visible-first");
        assertThat(json.writeValueAsString(blind)).doesNotContain("systemQuantity","differenceQuantity","differenceAmount","unitCost","DISCREPANCY");
        var member=members.findById(memberId).orElseThrow();member.softDelete();members.saveAndFlush(member);
        assertThatThrownBy(()->batches.save(document.getId(),command,"visible-first")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}

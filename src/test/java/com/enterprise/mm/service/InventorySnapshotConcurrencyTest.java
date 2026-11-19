package com.enterprise.mm.service;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.test.context.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers(disabledWithoutDocker=false)
@TestPropertySource(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","app.seed.enabled=false","mm.pilot.enabled-capabilities=","app.scheduling.enabled=false","app.firebase.enabled=false","mm.integrations.cbu.enabled=false"})
class InventorySnapshotConcurrencyTest {
    @Autowired UnitOfMeasureRepository b09Units;
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);r.add("spring.datasource.driver-class-name",DB::getDriverClassName);}
    @Autowired MaterialRepository b09Materials; @Autowired UserRepository b09Users;
    @Autowired InventoryDocumentService documents;
    @Autowired com.enterprise.mm.service.inventory.InventoryWorkspaceService workspace;
    @Autowired com.enterprise.mm.service.inventory.InventoryCountBatchService counts;
    @Autowired InventoryProcessingService posting;
    @Autowired AttachmentRepository attachments;
    @Autowired MaterialReservationService reservations;
    @Autowired QualityInspectionService inspections;
    @Autowired QualityInspectionRepository inspectionRepository;
    @Autowired StockMovementService movements;
    @Autowired InventoryScopeLockService locks;
    @Autowired InventoryAdjustmentPermitService permits;
    @Autowired WarehouseRepository warehouses;
    @Autowired StorageLocationRepository locations;
    @Autowired CommissionRepository commissions;
    @Autowired CommissionMemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private ExecutorService executor;
    private record Fixture(UUID warehouse,UUID location,UUID material,UUID secondMaterial,UUID commission){}
    @BeforeEach void setup(){authenticate();com.enterprise.mm.B09InventoryMasterFixture.ensure(b09Materials,b09Units,b09Users);executor=Executors.newFixedThreadPool(2);}
    @AfterEach void cleanup(){executor.shutdownNow();SecurityContextHolder.clearContext();TenantContext.clear();CompanyContext.clear();}
    private void authenticate(){TenantContext.setTenant("default");CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin","",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}
    private <T>Future<T> async(Supplier<T> action){return executor.submit(()->{authenticate();try{return action.get();}finally{SecurityContextHolder.clearContext();TenantContext.clear();CompanyContext.clear();}});}
    private void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Concurrent operation did not reach its checkpoint");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private Fixture fixture(){
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var w=new Warehouse();w.setName("Count concurrency warehouse "+suffix);w.setCode("IC-W-"+suffix);w.setStatus("ACTIVE");w=warehouses.saveAndFlush(w);
        var l=new StorageLocation();l.setName("Count rack "+suffix);l.setCode("IC-L-"+suffix);l.setStatus("ACTIVE");l.setWarehouseId(w.getId());l=locations.saveAndFlush(l);
        var materialIds=jdbc.queryForList("select id from materials where company_id=? and material_type='TMB' and is_delete=false and status='ACTIVE' order by id limit 2",UUID.class,CompanyContext.DEFAULT_COMPANY_ID);
        var c=new Commission();c.setName("Count commission "+suffix);c.setCode("IC-C-"+suffix);c.setStatus("ACTIVE");c.setType(CommissionType.INVENTORY);c.setActive(true);c.setValidFrom(LocalDate.now().minusDays(1));c=commissions.saveAndFlush(c);
        var user=jdbc.queryForObject("select id from users where company_id=? and username='admin' and is_delete=false",UUID.class,CompanyContext.DEFAULT_COMPANY_ID);
        var member=new CommissionMember();member.setCommissionId(c.getId());member.setUserId(user);member.setMemberName("Independent counter");member.setMemberRole(CommissionMemberRole.CHAIRMAN);member.setName("Count chair "+suffix);member.setCode("IC-CM-"+suffix);member.setStatus("ACTIVE");members.saveAndFlush(member);
        var f=new Fixture(w.getId(),l.getId(),materialIds.get(0),materialIds.get(1),c.getId());receipt(f,f.material(),f.location(),"10",null);return f;
    }
    private StockMovementResponse receipt(Fixture f,UUID material,UUID location,String quantity,String reference){
        var m=new StockMovement();m.setName("Inventory concurrency receipt");m.setMaterialId(material);m.setWarehouseId(f.warehouse());m.setStorageLocationId(location);m.setMovementType(MovementType.RECEIPT);m.setQuantity(new BigDecimal(quantity));m.setUnitCost(BigDecimal.TEN);m.setMovementDate(LocalDate.now());m.setReferenceType(reference==null?"INVENTORY_TEST_OPENING":reference);return movements.createAndPost(m);
    }
    private UUID plan(Fixture f,UUID material,UUID location){
        var r=new InventoryDocumentCreateRequest();r.setName("Scoped physical count");r.setWarehouseId(f.warehouse());r.setStorageLocationId(location);r.setMaterialIds(List.of(material));r.setCommissionId(f.commission());r.setFreezeOperations(true);r.setStatus(InventoryStatus.PLANNED);return documents.create(r).getId();
    }
    @Test void readableNumbersAreGeneratedConcurrentlyAndSearchable() throws Exception {
        var f=fixture();
        var futures=new ArrayList<Future<InventoryDocumentResponse>>();
        for(int i=0;i<6;i++) futures.add(async(()->documents.get(plan(f,f.material(),f.location()))));
        var created=new ArrayList<InventoryDocumentResponse>();
        for(var future:futures) created.add(future.get(30,TimeUnit.SECONDS));
        assertThat(created).extracting(InventoryDocumentResponse::getInventoryNumber).doesNotHaveDuplicates().allMatch(n->n.matches("INV-\\d{6,}"));
        var first=created.getFirst();
        var found=documents.search(new InventoryDocumentSearchRequest(first.getInventoryNumber(),null,null,null,null,false),org.springframework.data.domain.PageRequest.of(0,20));
        assertThat(found.getContent()).extracting(InventoryDocumentResponse::getId).containsExactly(first.getId());
        assertThat(LocalizedText.translate(first.getName(), Locale.ENGLISH)).isEqualTo("Scoped physical count");
    }
    @Test void numberMigrationBackfillsLegacyRowsAndContinuesSequence() throws Exception {
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.execute("create schema inventory_number_upgrade_test");
                statement.execute("set local search_path=inventory_number_upgrade_test");
                statement.execute("create table inventory_documents (id uuid primary key, created_at timestamp not null)");
                statement.execute("insert into inventory_documents values ('00000000-0000-0000-0000-000000000002','2026-02-01'),('00000000-0000-0000-0000-000000000001','2026-01-01')");
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ClassPathResource("db/migration/V69__readable_inventory_numbers.sql"));
                try(var rows=statement.executeQuery("select inventory_sequence from inventory_documents order by created_at")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(1);
                    assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(2);
                }
                try(var rows=statement.executeQuery("insert into inventory_documents(id,created_at) values(gen_random_uuid(),now()) returning inventory_sequence")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(3);
                }
            } finally { connection.rollback(); }
        }
    }
    @Test void operationalWorkspaceIsEnabledAndComparesPhysicalCountsWithoutChangingStock() {
        var f=fixture();var id=plan(f,f.material(),f.location());
        var planned=workspace.get(id);
        assertThat(planned.workspaceEnabled()).isTrue();
        assertThat(planned.freezeScope().active()).isFalse();
        assertThat(planned.lines()).isEmpty();

        var started=documents.start(id);var line=started.getItems().getFirst();
        assertThat(line.getSystemQuantity()).isEqualByComparingTo("10");
        assertThat(line.getCountedQuantity()).isNull();
        counts.save(id,new InventoryCountBatchDtos.BatchCommand(started.getVersion(),List.of(
            new InventoryCountBatchDtos.RowCommand(line.getId(),line.getVersion(),new BigDecimal("7"),false,"Physical shelf count")
        )),"count-"+UUID.randomUUID());

        var counted=workspace.get(id);
        assertThat(counted.freezeScope().active()).isTrue();
        assertThat(counted.lines().getFirst().getCountedQuantity()).isEqualByComparingTo("7");
        assertThat(counted.lines().getFirst().getDifferenceQuantity()).isEqualByComparingTo("-3");
        assertThat(counted.lines().getFirst().getDiscrepancyType()).isEqualTo(InventoryDiscrepancyType.SHORTAGE);
        assertThat(quantity(f)).isEqualByComparingTo("10");
        assertThat(documents.completeCounting(id).getStatus()).isEqualTo("DISCREPANCY_REVIEW");
    }
    @Test void overlappingStartsSerializeAndReplayDoesNotRebuildTheSnapshot()throws Exception {
        var f=fixture();var a=plan(f,f.material(),f.location());var b=plan(f,f.material(),f.location());var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        Supplier<String> first=()->{ready.countDown();await(go);try{documents.start(a);return "started";}catch(com.enterprise.mm.error.ConflictException e){return "blocked";}};
        var x=async(first);var y=async(()->{ready.countDown();await(go);try{documents.start(b);return "started";}catch(com.enterprise.mm.error.ConflictException e){return "blocked";}});await(ready);go.countDown();assertThat(List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("started","blocked");
        UUID started="IN_PROGRESS".equals(documents.get(a).getStatus())?a:b;
        var before=documents.get(started);var replay=documents.start(started);assertThat(replay.getSnapshotAt()).isEqualTo(before.getSnapshotAt());assertThat(replay.getItems()).extracting(InventoryItemResponse::getId).containsExactlyElementsOf(before.getItems().stream().map(InventoryItemResponse::getId).toList());assertThat(replay.getItems()).allSatisfy(i->assertThat(i.getCountedQuantity()).isNull());
    }
    @Test void snapshotWaitsForAnEarlierStockCommitAndIncludesIt()throws Exception {
        var f=fixture();var id=plan(f,f.material(),f.location());var changed=new CountDownLatch(1);var release=new CountDownLatch(1);
        var stock=async(()->new TransactionTemplate(transactions).execute(s->{receipt(f,f.material(),f.location(),"5",null);changed.countDown();await(release);return true;}));await(changed);
        var start=async(()->documents.start(id));Thread.sleep(150);assertThat(start.isDone()).isFalse();release.countDown();assertThat(stock.get(10,TimeUnit.SECONDS)).isTrue();
        assertThat(start.get(10,TimeUnit.SECONDS).getItems().stream().map(InventoryItemResponse::getSystemQuantity).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("15");
    }
    @Test void waitingStockOperationRechecksFreezeAfterSnapshotCommit()throws Exception {
        var f=fixture();var id=plan(f,f.material(),f.location());var snapshotted=new CountDownLatch(1);var release=new CountDownLatch(1);
        var start=async(()->new TransactionTemplate(transactions).execute(s->{documents.start(id);snapshotted.countDown();await(release);return true;}));await(snapshotted);
        var stock=async(()->receipt(f,f.material(),f.location(),"5",null));Thread.sleep(150);assertThat(stock.isDone()).isFalse();release.countDown();start.get(10,TimeUnit.SECONDS);
        assertThatThrownBy(()->stock.get(10,TimeUnit.SECONDS)).hasRootCauseMessage("Stock operations are blocked by the active physical count for this scope");assertThat(quantity(f)).isEqualByComparingTo("10");
    }
    @Test void approvedAndRejectedCountsKeepOnlyTheirExactMaterialAndLocationFrozen(){
        var f=fixture();var id=plan(f,f.material(),f.location());documents.start(id);
        for(String status:List.of("APPROVED","REJECTED")) {
            jdbc.update("update inventory_documents set status=?,approved_count_revision=count_revision where id=?",status,id);
            assertThatThrownBy(()->receipt(f,f.material(),f.location(),"1",null)).hasMessageContaining("active physical count");
            assertThatCode(()->receipt(f,f.secondMaterial(),f.location(),"1",null)).doesNotThrowAnyException();
            assertThatCode(()->receipt(f,f.material(),null,"1",null)).doesNotThrowAnyException();
        }
        documents.cancel(id);assertThatCode(()->receipt(f,f.material(),f.location(),"1",null)).doesNotThrowAnyException();
    }
    @Test void failedAdjustmentKeepsFreezeAndSuccessfulPostingReleasesIt(){
        var f=fixture();var id=plan(f,f.material(),f.location());var started=documents.start(id);var line=started.getItems().getFirst();
        jdbc.update("update inventory_items set counted_quantity=-1,line_status='RESOLVED',decision_type='WRITE_OFF_SHORTAGE',decision_count_revision=count_revision where id=?",line.getId());
        jdbc.update("update inventory_documents set status='APPROVED',approved_count_revision=count_revision,submitted_count_revision=count_revision,commission_approved_revision=count_revision,commission_approved_by='reviewer',finance_approved_revision=count_revision,finance_approved_by='finance' where id=?",id);
        assertThatThrownBy(()->posting.postAdjustments(id,new InventoryAdjustmentPostRequest(documents.get(id).getVersion(),documents.get(id).getApprovedCountRevision(),LocalDate.now()),"failed-count-"+id)).isInstanceOf(com.enterprise.mm.error.BusinessException.class);
        assertThat(documents.get(id).getStatus()).isEqualTo("APPROVED");assertThat(quantity(f)).isEqualByComparingTo("10");
        assertThatThrownBy(()->receipt(f,f.material(),f.location(),"1",null)).hasMessageContaining("active physical count");
        var proof=new Attachment();proof.setName("Count evidence");proof.setEntityType("INVENTORY_COUNT_EVIDENCE");proof.setEntityId(id);proof.setOriginalFileName("count.txt");proof.setContentType("text/plain");proof.setSizeBytes(1L);proof.setChecksumSha256("0".repeat(64));proof.setStorageKey("fixture");proof.setUploadedAt(java.time.LocalDateTime.now());var proofId=attachments.saveAndFlush(proof).getId();
        jdbc.update("update inventory_items set counted_quantity=8,difference_quantity=-2,decision_comment='Verified count',decision_evidence_ids=?::jsonb where id=?", "[\""+proofId+"\"]",line.getId());
        var result=posting.postAdjustments(id,new InventoryAdjustmentPostRequest(documents.get(id).getVersion(),documents.get(id).getApprovedCountRevision(),LocalDate.now()),"successful-count-"+id);assertThat(result.status()).isEqualTo("ADJUSTMENT_POSTED");assertThat(quantity(f)).isEqualByComparingTo("8");
        assertThatCode(()->receipt(f,f.material(),f.location(),"1",null)).doesNotThrowAnyException();
    }
    @Test void differentMaterialScopesCanCountAtTheSameTime() {
        var f=fixture();
        var first=documents.start(plan(f,f.material(),f.location()));
        var second=documents.start(plan(f,f.secondMaterial(),f.location()));
        assertThat(first.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(second.getStatus()).isEqualTo("IN_PROGRESS");
    }
    @Test void reservationsAndQuarantineCannotChangeTheFrozenSnapshot() {
        var f=fixture();documents.start(plan(f,f.material(),f.location()));
        var reservation=new MaterialReservationCreateRequest();
        reservation.setName("Frozen count reservation");reservation.setMaterialId(f.material());
        reservation.setWarehouseId(f.warehouse());reservation.setStorageLocationId(f.location());reservation.setQuantity(BigDecimal.ONE);
        assertThatThrownBy(()->reservations.create(reservation)).hasMessageContaining("active physical count");
        var inspection=new QualityInspection();inspection.setName("Frozen count inspection");inspection.setStatus("DRAFT");
        inspection.setMaterialId(f.material());inspection.setWarehouseId(f.warehouse());inspection.setStorageLocationId(f.location());inspection.setReceivedQuantity(BigDecimal.ONE);
        var id=inspectionRepository.saveAndFlush(inspection).getId();
        assertThatThrownBy(()->inspections.startQuarantine(id)).hasMessageContaining("active physical count");
        assertThat(jdbc.queryForObject("select sum(available_quantity) from stock_balances where warehouse_id=?",BigDecimal.class,f.warehouse())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select sum(reserved_quantity+quarantine_quantity) from stock_balances where warehouse_id=?",BigDecimal.class,f.warehouse())).isZero();
    }
    private BigDecimal quantity(Fixture f){return jdbc.queryForObject("select sum(quantity) from stock_balances where warehouse_id=? and material_id=? and storage_location_id=?",BigDecimal.class,f.warehouse(),f.material(),f.location());}
}

package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.InventoryCountBatchDtos.*;
import com.enterprise.mm.payload.InventoryReviewDtos.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import com.enterprise.mm.service.approval.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers(disabledWithoutDocker=false)
@TestPropertySource(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","app.seed.enabled=false","mm.pilot.enabled-capabilities=","app.scheduling.enabled=false","app.firebase.enabled=false","mm.integrations.cbu.enabled=false"})
class InventoryPostingConcurrencyTest {
    @Autowired UnitOfMeasureRepository b09Units;
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);r.add("spring.datasource.driver-class-name",DB::getDriverClassName);}
    @Autowired InventoryDocumentService lifecycle; @Autowired InventoryCountBatchService counts; @Autowired InventoryReviewService reviews;
    @Autowired InventoryProcessingService posting; @Autowired InventoryPostingReadinessService readiness; @Autowired InventoryFoundStockService found;
    @Autowired InventoryDocumentRepository documents; @Autowired InventoryItemRepository items; @Autowired StockMovementRepository movements;
    @Autowired StockMovementService stock; @Autowired WarehouseRepository warehouses; @Autowired MaterialRepository materials;
    @Autowired CommissionRepository commissions; @Autowired CommissionMemberRepository members; @Autowired UserRepository users;
    @Autowired ApprovalFlowAdminService flowAdmin; @Autowired ApprovalTaskRepository tasks; @Autowired ApprovalEngineService engine;
    @Autowired AttachmentRepository attachments; @Autowired InventoryPeriodCloseRepository periods; @Autowired StockBatchRepository batches; @Autowired JdbcTemplate jdbc;
    @Autowired InventoryAccessService access;
    @Autowired MockMvc http;
    private UUID id,warehouse,evidence; private String counter,checker,finance; private ExecutorService executor;
    @BeforeEach void fixture() {
        auth("admin","ADMIN");com.enterprise.mm.B09InventoryMasterFixture.ensure(materials,b09Units,users);executor=Executors.newFixedThreadPool(2);String suffix=UUID.randomUUID().toString().substring(0,8);
        var w=new Warehouse();w.setName("Posting warehouse "+suffix);warehouse=warehouses.saveAndFlush(w).getId();
        var selected=materials.findAll().stream().filter(m->CompanyContext.currentCompany().equals(m.getCompanyId())&&!m.isDeleted()&&m.getMaterialType()==MaterialType.TMB&&"ACTIVE".equals(m.getStatus())).limit(2).map(Material::getId).toList();
        for(var material:selected){var receipt=new StockMovement();receipt.setName("Count opening stock");receipt.setWarehouseId(warehouse);receipt.setMaterialId(material);receipt.setMovementType(MovementType.RECEIPT);receipt.setQuantity(new BigDecimal("100"));receipt.setUnitCost(new BigDecimal("15"));receipt.setMovementDate(LocalDate.now());stock.createAndPost(receipt);}
        var commission=new Commission();commission.setName("Posting commission "+suffix);commission.setType(CommissionType.INVENTORY);commission=commissions.saveAndFlush(commission);
        counter="counter-"+suffix;checker="checker-"+suffix;finance="finance-"+suffix;
        member(commission,counter,CommissionMemberRole.COUNTER,RoleName.DEPARTMENT_WORKER);member(commission,checker,CommissionMemberRole.CHECKER,RoleName.MM_MANAGER);user(finance,RoleName.FINANCE_CONTROLLER);
        var request=new InventoryDocumentCreateRequest();request.setName("Physical count posting");request.setWarehouseId(warehouse);request.setCommissionId(commission.getId());request.setMaterialIds(selected);request.setFreezeOperations(true);request.setStatus(InventoryStatus.PLANNED);id=lifecycle.create(request).getId();lifecycle.start(id);
        var a=new Attachment();a.setName("Count evidence");a.setEntityType("INVENTORY_COUNT_EVIDENCE");a.setEntityId(id);a.setOriginalFileName("count.txt");a.setContentType("text/plain");a.setSizeBytes(1L);a.setChecksumSha256("0".repeat(64));a.setStorageKey("fixture");a.setUploadedAt(LocalDateTime.now());evidence=attachments.saveAndFlush(a).getId();
        var first = new ApprovalDtos.ApprovalFlowStepRequest(null, "Commission review", 1, ApprovalStepType.STANDARD, null, null,
            ApprovalAssigneeType.ROLE, "MM_MANAGER", null, null, false, false, true, null);
        var last = new ApprovalDtos.ApprovalFlowStepRequest(null, "Finance approval", 2, ApprovalStepType.STANDARD, null, null,
            ApprovalAssigneeType.ROLE, "FINANCE_CONTROLLER", null, null, true, true, true, null);
        flowAdmin.create(new ApprovalDtos.ApprovalFlowRequest(CompanyContext.currentCompany(), null, "Inventory commission and finance",
            "INVENTORY_DOCUMENT", ApprovalFlowType.SEQUENTIAL, null, null, true, "ACTIVE", null, List.of(first, last), List.of()));
    }
    private User user(String username,RoleName role){var u=new User();u.setUsername(username);u.setName(username);u.setPasswordHash("fixture-only");u.setRoles(Set.of(role));return users.saveAndFlush(u);}
    private void member(Commission c,String username,CommissionMemberRole role,RoleName authority){var u=user(username,authority);var m=new CommissionMember();m.setName(username);m.setMemberName(username);m.setUserId(u.getId());m.setCommissionId(c.getId());m.setMemberRole(role);members.saveAndFlush(m);}
    private void auth(String name,String role){TenantContext.setTenant("default");CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,"",List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    @AfterEach void clear(){executor.shutdownNow();SecurityContextHolder.clearContext();TenantContext.clear();CompanyContext.clear();}
    private InventoryDocument doc(){return documents.findById(id).orElseThrow();}
    private List<InventoryItem> lines(){return items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse("default",id).stream().sorted(Comparator.comparing(InventoryItem::getId)).toList();}
    private void countAll(String quantity){auth(counter,"DEPARTMENT_WORKER");var command=new BatchCommand(doc().getVersion(),lines().stream().map(l->new RowCommand(l.getId(),l.getVersion(),new BigDecimal(quantity),false,null)).toList());assertThat(counts.save(id,command,UUID.randomUUID().toString()).rows()).allSatisfy(r->assertThat(r.outcome()).isEqualTo("SAVED"));}
    private void reviewAndApprove(){
        auth(checker,"MM_MANAGER");for(var line:lines())if(line.getDifferenceQuantity().signum()!=0)reviews.decide(line.getId(),new Decision(line.getVersion(),line.getCountRevision(),line.getDifferenceQuantity().signum()<0?"WRITE_OFF_SHORTAGE":"POST_SURPLUS","Verified observation",List.of(evidence),null));
        lifecycle.completeCounting(id);lifecycle.submitForApproval(id);engine.approveTask(pending(),"Commission approval");auth(finance,"FINANCE_CONTROLLER");engine.approveTask(pending(),"Finance approval");
    }
    private UUID pending(){return tasks.findFirstByTenantIdAndApprovalInstanceIdAndTaskStatusAndIsDeletedFalseOrderByStepOrder("default",doc().getApprovalInstanceId(),ApprovalTaskStatus.PENDING).orElseThrow().getId();}
    private InventoryAdjustmentPostRequest command(){var d=doc();return new InventoryAdjustmentPostRequest(d.getVersion(),d.getApprovedCountRevision(),LocalDate.now());}
    private BigDecimal quantity(){return jdbc.queryForObject("select coalesce(sum(quantity),0) from stock_balances where warehouse_id=? and is_delete=false",BigDecimal.class,warehouse);}
    private BigDecimal value(){return jdbc.queryForObject("select coalesce(sum(total_value),0) from stock_balances where warehouse_id=? and is_delete=false",BigDecimal.class,warehouse);}
    private long adjustmentCount(){return movements.findByTenantIdAndReferenceTypeAndReferenceIdAndIsDeletedFalse("default","InventoryDocument",id).size();}
    private <T>Future<T> async(Supplier<T> action){return executor.submit(()->{auth(finance,"FINANCE_CONTROLLER");try{return action.get();}finally{SecurityContextHolder.clearContext();TenantContext.clear();CompanyContext.clear();}});}
    private void await(CountDownLatch latch){try{assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}

    @Test void twoKeysAndLostResponseRetryPostEachDifferenceOnceThenClosureAddsNothing()throws Exception {
        countAll("97");reviewAndApprove();var c=command();assertThat(readiness.get(id,c.postingDate()).ready()).isTrue();
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        var a=async(()->{ready.countDown();await(go);return posting.postAdjustments(id,c,"post-a");});var b=async(()->{ready.countDown();await(go);return posting.postAdjustments(id,c,"post-b");});await(ready);go.countDown();var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
        assertThat(first.movementIds()).containsExactlyElementsOf(second.movementIds());assertThat(List.of(first.alreadyPosted(),second.alreadyPosted())).containsExactlyInAnyOrder(false,true);
        assertThat(posting.postAdjustments(id,c,"post-a")).isEqualTo(first);assertThat(adjustmentCount()).isEqualTo(2);assertThat(quantity()).isEqualByComparingTo("194");assertThat(value()).isEqualByComparingTo("2910");
        for(var line:lines()){var movement=movements.findById(line.getLinkedStockMovementId()).orElseThrow();assertThat(movement.getQuantity()).isEqualByComparingTo("3");assertThat(movement.getSourceInventoryItemId()).isEqualTo(line.getId());assertThat(movement.getMovementDate()).isEqualTo(c.postingDate());assertThat(movement.getStockBatchId()).isEqualTo(line.getStockBatchId());}
        auth(checker,"MM_MANAGER");lifecycle.close(id);assertThat(adjustmentCount()).isEqualTo(2);assertThat(quantity()).isEqualByComparingTo("194");
        auth(finance,"FINANCE_CONTROLLER");assertThat(posting.postAdjustments(id,c,"after-close").alreadyPosted()).isTrue();
    }
    @Test void secondLineStockFailureRollsBackFirstMovementAndKeepsFreezeForRetry() {
        countAll("97");reviewAndApprove();var c=command();var last=lines().getLast();jdbc.update("update stock_balances set available_quantity=0 where warehouse_id=? and material_id=?",warehouse,last.getMaterialId());
        assertThatThrownBy(()->posting.postAdjustments(id,c,"retry-atomic")).isInstanceOf(RuntimeException.class);
        assertThat(adjustmentCount()).isZero();assertThat(quantity()).isEqualByComparingTo("200");assertThat(value()).isEqualByComparingTo("3000");assertThat(doc().getStatus()).isEqualTo("APPROVED");assertThat(doc().getPostingDate()).isNull();assertThat(lines()).allSatisfy(l->assertThat(l.getLinkedStockMovementId()).isNull());
        var receipt=new StockMovement();receipt.setName("Blocked receipt");receipt.setWarehouseId(warehouse);receipt.setMaterialId(last.getMaterialId());receipt.setMovementType(MovementType.RECEIPT);receipt.setQuantity(BigDecimal.ONE);receipt.setUnitCost(BigDecimal.TEN);receipt.setMovementDate(LocalDate.now());
        assertThatThrownBy(()->stock.createAndPost(receipt)).hasMessageContaining("active physical count");
        jdbc.update("update stock_balances set available_quantity=quantity where warehouse_id=?",warehouse);posting.postAdjustments(id,c,"retry-atomic");assertThat(adjustmentCount()).isEqualTo(2);assertThat(quantity()).isEqualByComparingTo("194");
    }
    @Test void explicitDateClosedPeriodStaleRevisionAndWrongActorDoNotPost() {
        countAll("97");reviewAndApprove();var c=command();
        auth(checker,"MM_MANAGER");assertThatThrownBy(()->posting.postAdjustments(id,c,"not-finance")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        auth(finance,"FINANCE_CONTROLLER");assertThatThrownBy(()->posting.postAdjustments(id,new InventoryAdjustmentPostRequest(c.expectedVersion(),c.approvedCountRevision(),null),"no-date")).hasMessageContaining("explicit posting date");
        assertThatThrownBy(()->posting.postAdjustments(id,new InventoryAdjustmentPostRequest(c.expectedVersion(),c.approvedCountRevision()-1,c.postingDate()),"stale-revision")).isInstanceOf(ConflictException.class);
        var closed=new InventoryPeriodClose();closed.setName("Closed posting period");closed.setWarehouseId(warehouse);closed.setStatus("CLOSED");closed.setPeriodStart(LocalDate.now().minusDays(1));closed.setPeriodEnd(LocalDate.now());periods.saveAndFlush(closed);
        assertThat(readiness.get(id,c.postingDate()).blockers()).contains("POSTING_PERIOD_CLOSED");assertThatThrownBy(()->posting.postAdjustments(id,c,"closed-period")).hasMessageContaining("POSTING_PERIOD_CLOSED");
        assertThat(doc().getFinanceApprovedRevision()).isNull();
        assertThat(readiness.get(id,c.postingDate()).blockers()).doesNotContain("FINANCE_APPROVAL_REQUIRED", "COMMISSION_APPROVAL_REQUIRED");
        CompanyContext.setCompany(UUID.randomUUID());assertThatThrownBy(()->posting.postAdjustments(id,c,"wrong-company")).isInstanceOf(NotFoundException.class);CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        assertThat(adjustmentCount()).isZero();assertThat(quantity()).isEqualByComparingTo("200");
    }
    private void finishReview() {
        auth(checker,"MM_MANAGER");
        for(var line:lines())if(line.getDifferenceQuantity().signum()!=0)
            reviews.decide(line.getId(),new Decision(line.getVersion(),line.getCountRevision(),"WRITE_OFF_SHORTAGE","Verified observation",List.of(evidence),null));
        lifecycle.completeCounting(id);
    }
    private String configureOperationsFlow(boolean commentRequired) {
        auth("admin","ADMIN");var reviewer=user("operations-"+id,RoleName.PROCUREMENT_MANAGER);
        var first=new ApprovalDtos.ApprovalFlowStepRequest(null,"Operations check",1,ApprovalStepType.STANDARD,null,null,
            ApprovalAssigneeType.USER,null,reviewer.getId(),reviewer.getUsername(),false,false,commentRequired,null);
        var last=new ApprovalDtos.ApprovalFlowStepRequest(null,"Operations approval",2,ApprovalStepType.STANDARD,null,null,
            ApprovalAssigneeType.ROLE,"PROCUREMENT_MANAGER",null,null,true,true,false,null);
        flowAdmin.create(new ApprovalDtos.ApprovalFlowRequest(CompanyContext.currentCompany(),null,"Configured operations approval",
            "INVENTORY_DOCUMENT",ApprovalFlowType.SEQUENTIAL,null,null,true,"ACTIVE",null,List.of(first,last),List.of()));
        auth(checker,"MM_MANAGER");lifecycle.submitForApproval(id);return reviewer.getUsername();
    }
    @Test void configuredApproversCanApproveShortagesWithoutCommissionMembershipOrFinanceApproval() {
        countAll("97");finishReview();var reviewer=configureOperationsFlow(false);
        auth(counter,"DEPARTMENT_WORKER");assertThat(access.canSeeSystemQuantity(access.document(id))).isFalse();
        assertThatThrownBy(()->engine.approveTask(pending(),(String)null)).isInstanceOf(BusinessException.class);
        auth(reviewer,"PROCUREMENT_MANAGER");
        assertThat(access.canSeeSystemQuantity(access.document(id))).isTrue();
        engine.approveTask(pending(),(String)null);assertThat(doc().getStatus()).isEqualTo("WAITING_APPROVAL");
        engine.approveTask(pending(),(String)null);assertThat(doc().getStatus()).isEqualTo("APPROVED");
        assertThat(doc().getCommissionApprovedRevision()).isNull();assertThat(doc().getFinanceApprovedRevision()).isNull();
        auth(finance,"FINANCE_CONTROLLER");assertThat(readiness.get(id,LocalDate.now()).ready()).isTrue();
        posting.postAdjustments(id,command(),"configured-flow");assertThat(quantity()).isEqualByComparingTo("194");
    }
    @Test void approvalTaskDetailLoadsMatchedRowsAlongsideDiscrepanciesOverHttp() throws Exception {
        countAll("100");var changed=lines().getFirst();
        counts.save(id,new BatchCommand(doc().getVersion(),List.of(new RowCommand(changed.getId(),changed.getVersion(),new BigDecimal("97"),false,null))),"detail-count");
        finishReview();var reviewer=configureOperationsFlow(false);auth(reviewer,"PROCUREMENT_MANAGER");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/approval-tasks/"+pending()+"/detail")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(reviewer).roles("PROCUREMENT_MANAGER"))
                .header("X-Tenant-Id","default").header("X-Company-Id",CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.sourceLines.status").value("READY"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.sourceLines.items.length()").value(2))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.evidence.status").value("READY"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.evidence.items.length()").value(1));
    }
    @Test void configuredParallelFlowAllowsItsAssignedCounterAndCreatorWithoutExtraApprovalRules() {
        countAll("97");finishReview();auth("admin","ADMIN");
        var creator=users.findByTenantIdAndUsernameAndIsDeletedFalse("default","admin").orElseThrow();
        var countingUser=users.findByTenantIdAndUsernameAndIsDeletedFalse("default",counter).orElseThrow();
        flowAdmin.create(new ApprovalDtos.ApprovalFlowRequest(CompanyContext.currentCompany(),null,"Configured parallel approval",
            "INVENTORY_DOCUMENT",ApprovalFlowType.PARALLEL_ALL,null,null,true,"ACTIVE",null,List.of(),List.of(
                new ApprovalDtos.ApprovalFlowParallelAssigneeRequest(creator.getId(),creator.getUsername()),
                new ApprovalDtos.ApprovalFlowParallelAssigneeRequest(countingUser.getId(),countingUser.getUsername()))));
        auth(checker,"MM_MANAGER");lifecycle.submitForApproval(id);
        var assigned=tasks.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder("default",doc().getApprovalInstanceId());
        auth(counter,"DEPARTMENT_WORKER");assertThat(access.canSeeSystemQuantity(access.document(id))).isTrue();
        engine.approveTask(assigned.stream().filter(t->counter.equals(t.getAssigneeUsername())).findFirst().orElseThrow().getId(),(String)null);
        assertThat(doc().getStatus()).isEqualTo("WAITING_APPROVAL");
        auth("admin","ADMIN");engine.approveTask(assigned.stream().filter(t->"admin".equals(t.getAssigneeUsername())).findFirst().orElseThrow().getId(),(String)null);
        assertThat(doc().getStatus()).isEqualTo("APPROVED");assertThat(readiness.get(id,LocalDate.now()).ready()).isTrue();
    }
    @Test void configuredCommentRequirementAndReturnStillWorkForNonCommissionApprover() {
        countAll("97");finishReview();var reviewer=configureOperationsFlow(true);
        auth(reviewer,"PROCUREMENT_MANAGER");var task=pending();
        assertThatThrownBy(()->engine.approveTask(task,(String)null)).hasMessageContaining("Comment is required");
        assertThat(doc().getStatus()).isEqualTo("WAITING_APPROVAL");
        engine.approveTask(task,"Configured required comment");
        var revision=doc().getCountRevision();engine.rejectTask(pending(),null);
        auth("admin","ADMIN");assertThat(doc().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(doc().getApprovalInstanceId()).isNull();assertThat(doc().getCountRevision()).isEqualTo(revision+1);
        auth(reviewer,"PROCUREMENT_MANAGER");assertThatThrownBy(()->access.document(id)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(adjustmentCount()).isZero();
    }
    @Test void newFoundSerialUsesObservedIdentityAndEvidencedValueOnlyAtSuccessfulPosting() {
        countAll("100");var original=lines().getFirst();var serial="FOUND-"+id;
        var added=found.add(id,new FoundStock(doc().getVersion(),original.getMaterialId(),warehouse,null,null,null,"COUNT-BATCH",serial,LocalDate.now().plusDays(90),BigDecimal.ONE,"Observed separate serial",List.of(evidence),null));
        assertThat(quantity()).isEqualByComparingTo("200");assertThat(added.getStockBatchId()).isNull();
        auth(finance,"FINANCE_CONTROLLER");var line=items.findById(added.getId()).orElseThrow();reviews.valueFoundStock(line.getId(),new Valuation(line.getVersion(),line.getCountRevision(),new BigDecimal("12.50"),"Evidenced replacement cost",List.of(evidence)));
        reviewAndApprove();posting.postAdjustments(id,command(),"found-post");assertThat(quantity()).isEqualByComparingTo("201");assertThat(value()).isEqualByComparingTo("3012.5");assertThat(adjustmentCount()).isEqualTo(1);
        line=items.findById(added.getId()).orElseThrow();var batch=batches.findById(line.getStockBatchId()).orElseThrow();assertThat(batch.getSerialNumber()).isEqualTo(serial);assertThat(batch.getExpirationDate()).isEqualTo(LocalDate.now().plusDays(90));assertThat(batch.getQuantity()).isEqualByComparingTo("1");
    }
    @Test void allMatchedPostingReleasesFreezeWithoutInventingMovementAndGenericReferencesCannotForgeAdjustment() {
        countAll("100");reviewAndApprove();var first=lines().getFirst();var result=posting.postAdjustments(id,command(),"matched");assertThat(result.movementIds()).isEmpty();assertThat(doc().getStatus()).isEqualTo("ADJUSTMENT_POSTED");
        var forged=new StockMovementCreateRequest();forged.setMaterialId(first.getMaterialId());forged.setWarehouseId(warehouse);forged.setQuantity(BigDecimal.ONE);forged.setMovementType(MovementType.ADJUSTMENT);forged.setReferenceType("InventoryDocument");forged.setReferenceId(id);
        assertThatThrownBy(()->stock.create(forged)).hasMessageContaining("approved inventory workflow");assertThat(adjustmentCount()).isZero();
        auth("admin","ADMIN");var receipt=new StockMovement();receipt.setName("After freeze");receipt.setMaterialId(first.getMaterialId());receipt.setWarehouseId(warehouse);receipt.setMovementType(MovementType.RECEIPT);receipt.setQuantity(BigDecimal.ONE);receipt.setUnitCost(BigDecimal.TEN);receipt.setMovementDate(LocalDate.now());stock.createAndPost(receipt);assertThat(quantity()).isEqualByComparingTo("201");
    }
}

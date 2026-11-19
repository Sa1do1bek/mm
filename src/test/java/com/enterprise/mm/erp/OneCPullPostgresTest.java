package com.enterprise.mm.erp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.error.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="MM_PERIOD_PG_URL",matches=".+")
class OneCPullPostgresTest {
    final ObjectMapper mapper=new ObjectMapper();
    final String tenant="native-pull-test",schema="pull_test_"+UUID.randomUUID().toString().replace("-","");
    final UUID company=UUID.randomUUID(),first=UUID.randomUUID(),second=UUID.randomUUID(),nativeRef=UUID.randomUUID();
    JdbcTemplate admin,jdbc;TransactionTemplate tx;OneCPullExchangeService service;
    @BeforeEach void setup() {
        String url=System.getenv("MM_PERIOD_PG_URL"),user=System.getenv().getOrDefault("MM_PERIOD_PG_USER","mm_local"),password=System.getenv().getOrDefault("MM_PERIOD_PG_PASSWORD","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("create table erp_exchange_connection(tenant_id text,company_id uuid,enabled boolean)");
        jdbc.execute("""
            create table erp_exchange_envelope(id uuid primary key,tenant_id text,company_id uuid,operation text,payload text,delivery_state text,
                delivery_order bigint,confirm_requested boolean default false,remote_document_ref uuid,remote_posted boolean,rejection_reason text,
                attempt_count integer default 0,next_attempt_at timestamp default current_timestamp)
            """);
        jdbc.execute("create table erp_period_review_run(id uuid primary key)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V93__one_c_initiated_queue.sql")).execute(ds);
        jdbc.update("insert into erp_exchange_connection values(?,?,true)",tenant,company);
        TenantContext.setTenant(tenant);CompanyContext.setCompany(company);
        service=new OneCPullExchangeService(jdbc,mapper,new OneCTransport(true));
        envelope(first,1);envelope(second,2);
    }
    void envelope(UUID id,int order){jdbc.update("insert into erp_exchange_envelope(id,tenant_id,company_id,operation,payload,delivery_state,delivery_order) values(?,?,?,'RECEIPT',?,'PENDING',?)",id,tenant,company,mapper.createObjectNode().put("eventId",id.toString()).toString(),order);}
    @AfterEach void cleanup(){if(admin!=null)admin.execute("drop schema if exists "+schema+" cascade");TenantContext.clear();CompanyContext.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    JsonNode poll(String worker){return poll(worker,false);}
    JsonNode poll(String worker, boolean acceptsNativePosting) {
        var request = mapper.createObjectNode().put("protocolVersion",1).put("workerId",worker).put("limit",1);
        if (acceptsNativePosting) request.put("nativePostingV1",true);
        return mapper.valueToTree(tx.execute(s->service.poll(request)));
    }
    ObjectNode ack(JsonNode task,boolean posted,UUID ref){return mapper.createObjectNode().put("protocolVersion",1).put("eventId",task.path("eventId").asText()).put("documentRef",ref.toString()).put("posted",posted).put("status",posted?"POSTED":"AWAITING_CONFIRMATION");}
    ObjectNode result(JsonNode task,JsonNode data){var request=mapper.createObjectNode().put("protocolVersion",1).put("leaseToken",task.path("leaseToken").asText()).put("success",true);request.set("result",data);return request;}
    void complete(JsonNode task,JsonNode data){tx.executeWithoutResult(s->service.result(UUID.fromString(task.path("taskId").asText()),result(task,data)));}
    @Test void duplicateResultsDoNotRepostAndConfirmationBlocksLaterDocuments() {
        JsonNode task=poll("worker-a").path("tasks").get(0);assertThat(task.path("eventId").asText()).isEqualTo(first.toString());
        assertThat(poll("worker-a").path("tasks").get(0)).isEqualTo(task);
        assertThat(poll("worker-b").path("tasks")).isEmpty();
        var ack=ack(task,false,nativeRef);complete(task,ack);complete(task,ack);
        assertThat(jdbc.queryForObject("select attempt_count from erp_exchange_envelope where id=?",Integer.class,first)).isEqualTo(1);
        assertThat(poll("worker-a").path("tasks")).isEmpty();
        jdbc.update("update erp_exchange_envelope set confirm_requested=true where id=?",first);
        JsonNode confirm=poll("worker-a").path("tasks").get(0);assertThat(confirm.path("type").asText()).isEqualTo("CONFIRM");
        assertThatThrownBy(()->complete(confirm,ack(confirm,true,UUID.randomUUID()))).isInstanceOf(ConflictException.class);
        assertThat(poll("worker-b").path("tasks")).isEmpty();
        complete(confirm,ack(confirm,true,nativeRef));
        assertThat(poll("worker-a").path("tasks").get(0).path("eventId").asText()).isEqualTo(second.toString());
        assertThat(jdbc.queryForObject("select remote_document_ref from erp_exchange_envelope where id=?",UUID.class,first)).isEqualTo(nativeRef);
    }
    @Test void leaseExpiryFencesStaleResultAndResultsCannotCrossScope() {
        var old=poll("worker-a").path("tasks").get(0);
        jdbc.update("update one_c_pull_task set lease_expires_at=current_timestamp-interval '1 second'");
        var renewed=poll("worker-b").path("tasks").get(0);
        assertThat(renewed.path("taskId")).isEqualTo(old.path("taskId"));assertThat(renewed.path("leaseToken")).isNotEqualTo(old.path("leaseToken"));
        assertThatThrownBy(()->complete(old,ack(old,true,nativeRef))).isInstanceOf(BadRequestException.class);
        CompanyContext.setCompany(UUID.randomUUID());assertThatThrownBy(()->complete(renewed,ack(renewed,true,nativeRef))).isInstanceOf(NotFoundException.class);CompanyContext.setCompany(company);
        TenantContext.setTenant("other-tenant");assertThatThrownBy(()->complete(renewed,ack(renewed,true,nativeRef))).isInstanceOf(NotFoundException.class);TenantContext.setTenant(tenant);
        complete(renewed,ack(renewed,true,nativeRef));
        assertThatThrownBy(()->complete(renewed,ack(renewed,true,UUID.randomUUID()))).isInstanceOf(ConflictException.class);
    }

    @Test void mappedTasksAreFrozenAndOnlyCapableWorkerCanLeaseOrConfirm() {
        var profile = installMappingForFirstIssue(true);
        assertThat(poll("legacy").path("tasks")).isEmpty();
        var task = poll("native",true).path("tasks").get(0);
        var original = task.path("payload");
        assertThat(original.at("/lines/0/nativeWarehouseId").isTextual()).isTrue();
        assertThat(original.at("/nativePosting/issue/expenseAccountRef").isTextual()).isTrue();
        assertThat(poll("native").path("tasks")).isEmpty();
        jdbc.update("update one_c_pull_task set lease_expires_at=current_timestamp-interval '1 second'");
        assertThat(poll("legacy").path("tasks")).isEmpty();
        task = poll("native",true).path("tasks").get(0);
        assertThat(task.path("payload")).isEqualTo(original);
        complete(task,ack(task,false,nativeRef));
        ((ObjectNode) profile.path("nativePosting")).put("organizationRef",UUID.randomUUID().toString());
        service = new OneCPullExchangeService(jdbc,mapper,new OneCTransport(true),new OneCNativePostingProfile(profile));
        jdbc.update("update erp_exchange_envelope set confirm_requested=true where id=?",first);
        assertThat(poll("legacy").path("tasks")).isEmpty();
        var confirm = poll("native",true).path("tasks").get(0);
        assertThat(confirm.path("type").asText()).isEqualTo("CONFIRM");
        assertThat(confirm.path("payload")).isEqualTo(original);
        complete(confirm,ack(confirm,true,nativeRef));
    }

    @Test void missingMappingIsVisibleAndNeverDeliversPartialDocument() {
        var profile = installMappingForFirstIssue(false);
        assertThat(poll("native",true).path("tasks")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from one_c_pull_task",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select rejection_reason from erp_exchange_envelope where id=?",String.class,first))
            .startsWith("MAPPING_REQUIRED: Warehouse ");
        String warehouse = readPayload(first).at("/lines/0/warehouseId").asText();
        ((ObjectNode) profile.path("warehouses")).put(warehouse,UUID.randomUUID().toString());
        service = new OneCPullExchangeService(jdbc,mapper,new OneCTransport(true),new OneCNativePostingProfile(profile));
        assertThat(poll("native",true).path("tasks")).hasSize(1);
        assertThat(jdbc.queryForObject("select rejection_reason from erp_exchange_envelope where id=?",String.class,first))
            .isNull();
    }

    private JsonNode readPayload(UUID id) {
        return service.read(jdbc.queryForObject("select payload from erp_exchange_envelope where id=?",String.class,id));
    }

    private ObjectNode installMappingForFirstIssue(boolean includeWarehouse) {
        UUID material = UUID.randomUUID(), warehouse = UUID.randomUUID();
        var document = mapper.createObjectNode().put("eventId",first.toString()).put("documentKind","ISSUE")
            .put("tenantId",tenant).put("companyId",company.toString());
        document.putArray("lines").addObject().put("materialId",material.toString()).put("warehouseId",warehouse.toString());
        jdbc.update("update erp_exchange_envelope set payload=?,operation='ISSUE' where id=?",document.toString(),first);
        var profile = mapper.createObjectNode().put("tenantId",tenant).put("companyId",company.toString());
        var posting = profile.putObject("nativePosting").put("organizationRef",UUID.randomUUID().toString());
        posting.putObject("issue").put("expenseAccountRef",UUID.randomUUID().toString())
            .put("expenseCodeRef",UUID.randomUUID().toString());
        profile.putObject("materials").putObject(material.toString()).put("nativeMaterialId",UUID.randomUUID().toString())
            .put("nativeAccountId",UUID.randomUUID().toString()).put("nativeUnitId",UUID.randomUUID().toString());
        var warehouses = profile.putObject("warehouses");
        if (includeWarehouse) warehouses.put(warehouse.toString(),UUID.randomUUID().toString());
        service = new OneCPullExchangeService(jdbc,mapper,new OneCTransport(true),new OneCNativePostingProfile(profile));
        return profile;
    }
    @Test void failuresRetryOnlyAfterDelayAndDuplicateFailuresDoNotIncrementAttempts() {
        var task=poll("worker-a").path("tasks").get(0);UUID id=UUID.fromString(task.path("taskId").asText());
        var failure=mapper.createObjectNode().put("protocolVersion",1).put("leaseToken",task.path("leaseToken").asText()).put("success",false).put("error","Native validation failed");
        tx.executeWithoutResult(s->service.result(id,failure));tx.executeWithoutResult(s->service.result(id,failure));
        assertThat(jdbc.queryForObject("select attempt_count from erp_exchange_envelope where id=?",Integer.class,first)).isEqualTo(1);
        assertThat(poll("worker-b").path("tasks")).isEmpty();
        jdbc.update("update one_c_pull_task set next_attempt_at=current_timestamp");
        var retry=poll("worker-b").path("tasks").get(0);assertThat(retry.path("taskId")).isEqualTo(task.path("taskId"));
        complete(retry,ack(retry,true,nativeRef));
    }
    @Test void concurrentWorkersCannotLeaseTwoTasks() throws Exception {
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call=()->{TenantContext.setTenant(tenant);CompanyContext.setCompany(company);try{gate.await();return poll("worker-"+Thread.currentThread().threadId()).path("tasks").size();}finally{TenantContext.clear();CompanyContext.clear();}};
            var a=pool.submit(call);var b=pool.submit(call);gate.countDown();assertThat(a.get()+b.get()).isEqualTo(1);
        } finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("select count(*) from one_c_pull_task where state='LEASED'",Integer.class)).isEqualTo(1);
    }
    @Test void balanceEvidenceIdentityAndCompletenessValidatedBeforeAcceptance() {
        jdbc.update("update erp_exchange_envelope set delivery_state='POSTED'");
        UUID id=UUID.randomUUID();jdbc.update("insert into one_c_pull_task(id,tenant_id,company_id,task_type,event_id,payload,query_type,requested_by) values(?,?,?,'BALANCES',?,?,'BALANCE','reviewer')",id,tenant,company,id,"{\"date\":\"2025-04-19\"}");
        var task=poll("worker").path("tasks").get(0);
        var evidence=mapper.createObjectNode().put("protocolVersion",1).put("tenantId",tenant).put("companyId",company.toString()).put("date","2025-04-19").put("basis","END_OF_DAY").put("complete",false);
        assertThatThrownBy(()->complete(task,evidence)).isInstanceOf(BadRequestException.class);
        UUID material=UUID.randomUUID(),warehouse=UUID.randomUUID();evidence.put("complete",true);var scope=evidence.putObject("scope");scope.putArray("materialIds").add(material.toString());scope.putArray("warehouseIds").add(warehouse.toString());
        evidence.putArray("balances").addObject().put("materialId",material.toString()).put("warehouseId",warehouse.toString()).put("quantity","0").put("amount","0");
        complete(task,evidence);complete(task,evidence);
        assertThat(jdbc.queryForObject("select state from one_c_pull_task where id=?",String.class,id)).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("select result from one_c_pull_task where id=?",String.class,id)).contains("END_OF_DAY");
    }
    @Test void queryJobsAreOwnerScopedDeduplicatePendingAndPersistDerivedReportOnce() {
        jdbc.update("update erp_exchange_envelope set delivery_state='POSTED'");
        var periodService=mock(ErpPeriodReviewService.class);var balanceService=mock(ErpReconciliationService.class);
        var jobs=new OneCQueryJobService(jdbc,service,new OneCTransport(true),periodService,balanceService);
        var security=org.springframework.security.core.context.SecurityContextHolder.getContext();
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("reviewer","ignored",List.of()));
        LocalDate date=LocalDate.of(2025,4,19);
        var job=tx.execute(status->jobs.create("BALANCE",null,date));UUID id=(UUID)job.get("id");
        assertThat(tx.execute(status->jobs.create("BALANCE",null,date)).get("id")).isEqualTo(id);
        assertThat(tx.execute(status->jobs.get(id)).get("status")).isEqualTo("PENDING");
        var task=poll("worker").path("tasks").get(0);UUID material=UUID.randomUUID(),warehouse=UUID.randomUUID();
        var evidence=mapper.createObjectNode().put("protocolVersion",1).put("tenantId",tenant).put("companyId",company.toString()).put("date",date.toString()).put("basis","END_OF_DAY").put("complete",true);
        var scope=evidence.putObject("scope");scope.putArray("materialIds").add(material.toString());scope.putArray("warehouseIds").add(warehouse.toString());
        evidence.putArray("balances").addObject().put("materialId",material.toString()).put("warehouseId",warehouse.toString()).put("quantity","0").put("amount","0");
        complete(task,evidence);
        when(balanceService.reconcileFromEvidence(eq(tenant),eq(company),eq(date),any())).thenReturn(Map.of("stockChanged",false,"differenceCount",0,"scope",Map.of("warehouseIds",List.of(warehouse))));
        var done=tx.execute(status->jobs.get(id));assertThat(done.get("status")).isEqualTo("COMPLETE");assertThat(mapper.valueToTree(done).path("result").path("stockChanged").asBoolean()).isFalse();
        tx.execute(status->jobs.get(id));verify(balanceService,times(1)).reconcileFromEvidence(eq(tenant),eq(company),eq(date),any());
        assertThat(jdbc.queryForObject("select report_result from one_c_pull_task where id=?",String.class,id)).contains("differenceCount");
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("other-reviewer","ignored",List.of()));
        assertThatThrownBy(()->tx.execute(status->jobs.get(id))).isInstanceOf(NotFoundException.class);
        CompanyContext.setCompany(UUID.randomUUID());assertThatThrownBy(()->tx.execute(status->jobs.get(id))).isInstanceOf(NotFoundException.class);
    }

}

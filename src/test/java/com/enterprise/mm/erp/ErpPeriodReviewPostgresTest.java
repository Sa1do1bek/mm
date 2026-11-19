package com.enterprise.mm.erp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real PostgreSQL queries and audit persistence, in a disposable schema independent of MM data. */
@EnabledIfEnvironmentVariable(named="MM_PERIOD_PG_URL",matches=".+")
class ErpPeriodReviewPostgresTest {
    final ObjectMapper mapper=new ObjectMapper();
    final String tenant="period-db-test",schema="period_test_"+UUID.randomUUID().toString().replace("-","");
    final UUID company=UUID.randomUUID(),material=UUID.randomUUID(),warehouse=UUID.randomUUID(),balance=UUID.randomUUID(),movement=UUID.randomUUID(),document=UUID.randomUUID(),nativeDocument=UUID.randomUUID(),envelope=UUID.randomUUID();
    final LocalDate date=LocalDate.of(2025,4,19);
    JdbcTemplate admin,jdbc;HttpServer server;ErpPeriodReviewService service;RoleDataScopeService access;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("MM_PERIOD_PG_URL"),user=System.getenv().getOrDefault("MM_PERIOD_PG_USER","mm_local"),password=System.getenv().getOrDefault("MM_PERIOD_PG_PASSWORD","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);
        for(String ddl:List.of(
            "create table warehouses(id uuid,tenant_id text,company_id uuid,is_delete boolean,name text)",
            "create table materials(id uuid,tenant_id text,company_id uuid,is_delete boolean,name text,unit_of_measure_id uuid)",
            "create table unit_of_measures(id uuid,tenant_id text,company_id uuid,measurement_name_short_ru text,measurement_name_short text,code text)",
            "create table stock_balances(id uuid,tenant_id text,company_id uuid,material_id uuid,warehouse_id uuid,is_delete boolean,quantity numeric(22,7),total_value numeric(19,4))",
            "create table stock_movements(id uuid,tenant_id text,company_id uuid,material_id uuid,warehouse_id uuid,target_warehouse_id uuid,movement_date date,posted_at timestamp,reference_type text,reference_id uuid,document_id uuid,code text,movement_type text)",
            "create table stock_movement_allocations(id uuid,tenant_id text,company_id uuid,movement_id uuid,material_id uuid,balance_id uuid,on_hand_delta numeric(22,7),total_value_delta numeric(19,4))",
            "create table warehouse_posting_batches(id uuid,tenant_id text,company_id uuid,status text)",
            "create table warehouse_posting_effects(id uuid,batch_id uuid,warehouse_id uuid,effect_type text)",
            "create table erp_exchange_connection(tenant_id text,company_id uuid,enabled boolean,base_url text)",
            "create table erp_exchange_envelope(id uuid,tenant_id text,company_id uuid,document_id uuid,document_kind text,operation text,posting_date date,remote_document_ref uuid,remote_posted boolean,delivery_state text,rejection_reason text,payload text)",
            "create table one_c_api_inbox(tenant_id text,company_id uuid,event_id uuid,document_id uuid,document_kind text)"))jdbc.execute(ddl);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V92__erp_period_reviews.sql")).execute(ds);
        jdbc.update("insert into warehouses values(?,?,?,false,'Warehouse')",warehouse,tenant,company);
        jdbc.update("insert into materials values(?,?,?,false,'Material',null)",material,tenant,company);
        jdbc.update("insert into stock_balances values(?,?,?,?,?,false,1,10)",balance,tenant,company,material,warehouse);
        // UI-created documents can use document_id while reference_id and reference_type remain null.
        jdbc.update("insert into stock_movements values(?,?,?,?,?,null,?,?,null,null,?,'UI receipt','RECEIPT')",movement,tenant,company,material,warehouse,date,date.atStartOfDay(),document);
        jdbc.update("insert into stock_movement_allocations values(?,?,?,?,?,?,1,10)",UUID.randomUUID(),tenant,company,movement,material,balance);
        var event=mapper.createObjectNode();event.putArray("lines").addObject().put("materialId",material.toString()).put("warehouseId",warehouse.toString());
        jdbc.update("insert into erp_exchange_envelope values(?,?,?,?, 'RECEIPT','RECEIPT',?, ?,true,'POSTED',null,?)",envelope,tenant,company,document,date,nativeDocument,event.toString());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mm/v1/balances",exchange->{byte[] bytes=mapper.writeValueAsBytes(evidence());exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();jdbc.update("insert into erp_exchange_connection values(?,?,true,?)",tenant,company,base);
        TenantContext.setTenant(tenant);CompanyContext.setCompany(company);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reviewer","ignored",List.of()));
        access=mock(RoleDataScopeService.class);when(access.canAccessWarehouse(warehouse)).thenReturn(true);
        var named=new NamedParameterJdbcTemplate(ds);service=new ErpPeriodReviewService(jdbc,named,new InventoryStockAsOfQuery(named),access,new OneCApiAccess(base,"k".repeat(32),tenant,company.toString()),mapper);
    }
    ObjectNode evidence() {
        var data=mapper.createObjectNode();data.put("protocolVersion",1).put("tenantId",tenant).put("companyId",company.toString()).put("from",date.toString()).put("to",date.toString()).put("basis","DAILY_ACCOUNTING_TURNOVER").put("complete",true);
        var scope=data.putObject("scope");scope.putArray("materialIds").add(material.toString());scope.putArray("warehouseIds").add(warehouse.toString());
        data.putArray("openingBalances").add(position().put("quantity","0").put("amount","0"));data.putArray("closingBalances").add(position().put("quantity","1").put("amount","10"));
        data.putArray("days").addObject().put("date",date.toString()).putArray("turnovers").add(position().put("inQuantity","1").put("inAmount","10").put("outQuantity","0").put("outAmount","0"));
        data.putArray("documents").addObject().put("documentId",nativeDocument.toString()).put("date",date.toString()).put("kind","RECEIPT").put("label","Native receipt");return data;
    }
    ObjectNode position(){return mapper.createObjectNode().put("materialId",material.toString()).put("warehouseId",warehouse.toString());}
    @AfterEach void cleanup(){if(server!=null)server.stop(0);if(admin!=null)admin.execute("drop schema if exists "+schema+" cascade");TenantContext.clear();CompanyContext.clear();SecurityContextHolder.clearContext();}
    @ParameterizedTest @ValueSource(strings={"DOCUMENT","MOVEMENT","REFERENCE"})
    void persistsExactSnapshotReviewsAuditAndRejectsStaleOrForeignAccess(String source){
        if("MOVEMENT".equals(source)) {
            jdbc.update("update stock_movements set document_id=null");
            jdbc.update("update erp_exchange_envelope set document_id=?",movement);
        } else if("REFERENCE".equals(source)) {
            jdbc.update("update stock_movements set document_id=null,reference_id=?,reference_type='TRANSFER_DISPATCH'",document);
        }
        var run=service.generate("DAY",date);UUID id=(UUID)run.get("id");var json=mapper.valueToTree(run);
        assertThat(json.path("report").path("summary").path("hasDifferences").asBoolean()).isFalse();
        assertThat(json.path("report").path("documents").size()).isEqualTo(1);
        assertThat(service.history()).hasSize(1);
        var reviewed=service.review(id,"REVIEWED","Checked test receipt",0,String.valueOf(run.get("fingerprint")));
        assertThat(reviewed.get("reviewState")).isEqualTo("REVIEWED");assertThat(((Number)reviewed.get("reviewVersion")).longValue()).isEqualTo(1);
        assertThat(mapper.valueToTree(reviewed).path("audit").size()).isEqualTo(1);
        assertThatThrownBy(()->service.review(id,"REVIEWED","Outdated client",0,String.valueOf(run.get("fingerprint")))).isInstanceOf(ConflictException.class);
        jdbc.update("update erp_exchange_envelope set rejection_reason='changed delivery evidence'");
        assertThatThrownBy(()->service.review(id,"REVIEWED","Outdated source",1,String.valueOf(run.get("fingerprint")))).isInstanceOf(ConflictException.class).hasMessageContaining("изменились");
        jdbc.update("update erp_exchange_envelope set rejection_reason=null");
        service.generate("DAY",date);
        assertThatThrownBy(()->service.review(id,"REVIEWED","Outdated run",1,String.valueOf(run.get("fingerprint")))).isInstanceOf(ConflictException.class).hasMessageContaining("новая");
        CompanyContext.setCompany(UUID.randomUUID());assertThatThrownBy(()->service.get(id)).isInstanceOf(NotFoundException.class);CompanyContext.setCompany(company);
        when(access.canAccessWarehouse(warehouse)).thenReturn(false);assertThat(service.history()).isEmpty();assertThatThrownBy(()->service.get(id)).isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("select count(*) from erp_period_review_audit",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from stock_balances",java.math.BigDecimal.class)).isEqualByComparingTo("1");
    }
    @Test void nativePullReviewRequiresFreshMatchingScopedEvidenceAndKeepsOriginalSnapshot() {
        var ds=jdbc.getDataSource();
        jdbc.execute("alter table erp_exchange_envelope add primary key(id)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V93__one_c_initiated_queue.sql")).execute(ds);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"transport",new OneCTransport(true));
        UUID originalJob=UUID.randomUUID();insertEvidenceTask(originalJob);
        var run=service.generateFromEvidence("DAY",date,evidence(),originalJob);UUID id=(UUID)run.get("id");String fingerprint=String.valueOf(run.get("fingerprint"));
        assertThatThrownBy(()->service.review(id,"REVIEWED","Refresh required",0,fingerprint)).isInstanceOf(ConflictException.class).hasMessageContaining("Refresh");
        UUID refresh=UUID.randomUUID();insertEvidenceTask(refresh);
        // An identical refresh creates another saved snapshot without invalidating this exact reviewed snapshot.
        service.generateFromEvidence("DAY",date,evidence(),refresh);
        jdbc.update("update one_c_pull_task set completed_at=current_timestamp-interval '3 minutes' where id=?",refresh);
        assertThatThrownBy(()->service.review(id,"REVIEWED","Stale refresh",0,fingerprint,refresh)).isInstanceOf(ConflictException.class);
        jdbc.update("update one_c_pull_task set completed_at=current_timestamp,requested_by='another-reviewer' where id=?",refresh);
        assertThatThrownBy(()->service.review(id,"REVIEWED","Other owner",0,fingerprint,refresh)).isInstanceOf(ConflictException.class);
        jdbc.update("update one_c_pull_task set requested_by='reviewer' where id=?",refresh);
        var changed=evidence();((ObjectNode)changed.path("closingBalances").get(0)).put("quantity","2");((ObjectNode)changed.path("days").get(0).path("turnovers").get(0)).put("inQuantity","2");
        jdbc.update("update one_c_pull_task set result=? where id=?",changed.toString(),refresh);
        assertThatThrownBy(()->service.review(id,"REVIEWED","Native changed",0,fingerprint,refresh)).isInstanceOf(ConflictException.class).hasMessageContaining("изменились");
        jdbc.update("update one_c_pull_task set result=? where id=?",evidence().toString(),refresh);
        var reviewed=service.review(id,"REVIEWED","Fresh scoped evidence checked",0,fingerprint,refresh);
        assertThat(reviewed.get("reviewState")).isEqualTo("REVIEWED");
        assertThat(jdbc.queryForObject("select native_task_id from erp_period_review_run where id=?",UUID.class,id)).isEqualTo(originalJob);
        assertThat(jdbc.queryForObject("select count(*) from erp_period_review_audit where run_id=?",Integer.class,id)).isEqualTo(1);
    }
    void insertEvidenceTask(UUID id) {
        jdbc.update("""
            insert into one_c_pull_task(id,tenant_id,company_id,task_type,event_id,payload,state,result,query_type,period_type,requested_by,completed_at)
            values(?,?,?,'BALANCES',?,?,'COMPLETE',?,'PERIOD','DAY','reviewer',current_timestamp)
            """,id,tenant,company,id,"{\"date\":\""+date+"\",\"from\":\""+date+"\"}",evidence().toString());
    }

}

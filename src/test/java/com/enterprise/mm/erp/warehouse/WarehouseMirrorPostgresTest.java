package com.enterprise.mm.erp.warehouse;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.error.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@EnabledIfEnvironmentVariable(named="MM_WAREHOUSE_PG_URL",matches=".+")
class WarehouseMirrorPostgresTest {
    final ObjectMapper mapper=new ObjectMapper(); final UUID company=UUID.randomUUID();
    final String schema="warehouse_v2_"+UUID.randomUUID().toString().replace("-","");
    JdbcTemplate admin,jdbc; WarehouseMirrorService service; TransactionTemplate tx;
    @BeforeEach void setup(){
        String url=System.getenv("MM_WAREHOUSE_PG_URL"),user=System.getenv().getOrDefault("MM_WAREHOUSE_PG_USER","mm_local"),password=System.getenv().getOrDefault("MM_WAREHOUSE_PG_PASSWORD","");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V95__warehouse_native_revision_journal.sql"),new ClassPathResource("db/migration/V97__warehouse_history_recovery.sql")).execute(ds);
        jdbc.execute("create table warehouse_source_master_links(tenant_id text,company_id uuid,reference_kind text,source_id text,master_id uuid)");
        jdbc.execute("create table stock_balances(id uuid primary key,quantity numeric,total_value numeric)");
        jdbc.execute("create table inventory_cost_layers(id uuid primary key,remaining_quantity numeric,total_cost numeric)");
        jdbc.update("insert into stock_balances values(?,4,80)",UUID.randomUUID());jdbc.update("insert into inventory_cost_layers values(?,0,0)",UUID.randomUUID());
        service=proxied();
        TenantContext.setTenant("test");CompanyContext.setCompany(company);
    }
    WarehouseMirrorService proxied(){var factory=new org.springframework.aop.framework.ProxyFactory(new WarehouseMirrorService(jdbc,mapper,new WarehouseMirrorSettings(true,"test-instance","org")));factory.setProxyTargetClass(true);factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));return (WarehouseMirrorService)factory.getProxy();}
    @AfterEach void clean(){if(admin!=null)admin.execute("drop schema "+schema+" cascade");TenantContext.clear();CompanyContext.clear();}
    ObjectNode snapshot(int revision,String state){
        ObjectNode n=mapper.createObjectNode();n.put("protocolVersion",2).put("eventId",UUID.randomUUID().toString()).put("sourceInstanceId","test-instance").put("organizationId","org").put("documentType","Документ.ЛюбаяОперация").put("documentId","native-doc").put("revision",revision).put("state",state).put("documentDate","2025-04-20").put("posted",false).put("origin","ONE_C");
        var rows=n.putArray("rows");if(state.equals("ACTIVE")){rows.addObject().put("rowKey","DT:1").put("period","2025-04-20T12:00:00").put("side","DT").put("accountId","account").put("accountCode","1010").put("materialId","item").put("warehouseId","warehouse").put("quantity","-2.1250000").put("amount","-9.0001").put("currency","UZS").putObject("analytics");rows.addObject().put("rowKey","DT:2").put("period","2025-04-20T12:00:00").put("side","DT").put("accountId","account").put("accountCode","1010").put("materialId","item").put("warehouseId","warehouse2").put("quantity","0").put("amount","1.25").put("currency","UZS").putObject("analytics");}return n;
    }
    Map<String,Object> accept(ObjectNode n){return tx.execute(s->service.receive(n));}
    @Test void revisionsReplaceContributionsPreserveAuditAndNeverTouchOperationalStock(){
        var first=snapshot(1,"ACTIVE");accept(first);assertThat(accept(first).get("replayed")).isEqualTo(true);
        assertThat(service.positions(100,0)).hasSize(2);assertThat(jdbc.queryForObject("select sum(quantity) from one_c_warehouse_current_rows",java.math.BigDecimal.class)).isEqualByComparingTo("-2.125");
        assertThat(jdbc.queryForObject("select amount from one_c_warehouse_current_rows where quantity=0",java.math.BigDecimal.class)).isEqualByComparingTo("1.25");
        var third=snapshot(3,"EMPTY");accept(third);accept(snapshot(2,"ACTIVE"));assertThat(service.positions(100,0)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from one_c_warehouse_revisions",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select revision from one_c_warehouse_documents",Long.class)).isEqualTo(3L);
        accept(snapshot(4,"DELETED"));assertThat(service.coverage().get("operationalApplication")).isEqualTo("NOT_APPLIED");
        assertThatThrownBy(()->accept(snapshot(4,"DELETED"))).isInstanceOf(ConflictException.class);
        var changed=first.deepCopy();changed.put("revision",9);assertThatThrownBy(()->accept(changed)).isInstanceOf(ConflictException.class);
        var foreign=snapshot(5,"ACTIVE");foreign.put("organizationId","foreign");assertThatThrownBy(()->accept(foreign)).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("select quantity from stock_balances",java.math.BigDecimal.class)).isEqualByComparingTo("4");assertThat(jdbc.queryForObject("select total_value from stock_balances",java.math.BigDecimal.class)).isEqualByComparingTo("80");assertThat(jdbc.queryForObject("select remaining_quantity from inventory_cost_layers",java.math.BigDecimal.class)).isEqualByComparingTo("0");
    }
    @Test void pagesAreDurableAtomicAndRequireCursorAndFence(){
        var run=tx.execute(s->service.createRun(mapper.createObjectNode().put("from","2025-01-01").put("to","2025-12-31").put("pageSize",10),"run-key"));
        String id=run.get("id").toString();var page=mapper.createObjectNode().put("runId",id).put("pageId","page-1").put("cursor","").put("nextCursor","next").put("snapshotSequence",8).put("expectedDocumentCount",3).put("completed",false);page.putArray("documents").add(snapshot(1,"ACTIVE"));
        tx.execute(s->service.page(page));assertThat(tx.execute(s->service.page(page)).get("replayed")).isEqualTo(true);
        service=proxied();assertThat(org.springframework.aop.support.AopUtils.isCglibProxy(service)).isTrue();assertThat(mapper.valueToTree(service.poll()).path("job").path("cursor").asText()).isEqualTo("next");
        var bad=page.deepCopy().put("pageId","bad").put("cursor","next").put("nextCursor","").put("completed",true);bad.withArray("documents").removeAll().add(snapshot(2,"EMPTY").put("documentId","second-doc")).add(snapshot(3,"ACTIVE").put("organizationId","foreign"));
        assertThatThrownBy(()->tx.execute(s->service.page(bad))).isInstanceOf(BadRequestException.class);assertThat(jdbc.queryForObject("select revision from one_c_warehouse_documents",Long.class)).isEqualTo(1L);
        var missing=bad.deepCopy();missing.withArray("documents").removeAll();assertThatThrownBy(()->service.page(missing)).isInstanceOf(ConflictException.class);
        var repeat=bad.deepCopy();repeat.withArray("documents").removeAll().add(snapshot(2,"EMPTY")).add(snapshot(1,"EMPTY").put("documentId","third-doc"));assertThatThrownBy(()->service.page(repeat)).isInstanceOf(ConflictException.class).hasMessageContaining("registrar");
        var wrongFence=bad.deepCopy().put("snapshotSequence",9);assertThatThrownBy(()->service.page(wrongFence)).isInstanceOf(ConflictException.class).hasMessageContaining("fence");
        bad.withArray("documents").removeAll().add(snapshot(2,"EMPTY").put("documentId","second-doc")).add(snapshot(1,"EMPTY").put("documentId","third-doc"));tx.execute(s->service.page(bad));assertThat(service.historyRuns()).hasSize(1);assertThat(service.historyRuns().getFirst().get("state")).isEqualTo("COMPLETE");
    }
    @Test void unknownAnalyticsValueOnlyScopeAndTamperRemainVisible(){
        var n=snapshot(1,"ACTIVE");((ObjectNode)n.path("rows").get(0)).putNull("materialId");accept(n);
        assertThat(service.documents(10,0,"2025-04-20","2025-04-20",n.path("documentType").asText(),"ACTIVE")).hasSize(1);
        var head=service.documents(10,0).getFirst();assertThat(mapper.valueToTree(service.document((UUID)head.get("id"))).path("versions").get(0).path("payload").path("rows").get(0).path("materialId").isNull()).isTrue();
        assertThat(service.coverage().get("unmappedRows")).isEqualTo(2L);
        var changed=n.deepCopy();((ObjectNode)changed.path("rows").get(0)).put("amount","33");assertThatThrownBy(()->accept(changed)).isInstanceOf(ConflictException.class);
        CompanyContext.setCompany(UUID.randomUUID());assertThat(service.documents(10,0)).isEmpty();assertThatThrownBy(()->service.document((UUID)head.get("id"))).isInstanceOf(NotFoundException.class);CompanyContext.setCompany(company);
    }
    @Test void failedHistoryDoesNotBlockLaterRunsAndRetryFencesStaleWorkers(){
        var request=mapper.createObjectNode().put("from","2025-02-22").put("to","2025-02-28").put("pageSize",10);
        UUID first=(UUID)service.createRun(request,"failed-first").get("id"),second=(UUID)service.createRun(request,"next-run").get("id");
        jdbc.update("update one_c_warehouse_history_runs set created_at=current_timestamp-interval '1 minute' where id=?",first);
        var failure=mapper.createObjectNode().put("runId",first.toString()).put("retryGeneration",0).put("attempts",10).put("terminal",true).put("error","Split oversized date range");
        assertThat(service.historyFailure(failure).get("state")).isEqualTo("FAILED");assertThat(service.historyFailure(failure).get("reportedAttempts")).isEqualTo(10);
        assertThat(mapper.valueToTree(service.poll()).path("job").path("id").asText()).isEqualTo(second.toString());
        assertThat(service.retryRun(first).get("retryGeneration")).isEqualTo(1L);assertThatThrownBy(()->service.historyFailure(failure)).isInstanceOf(ConflictException.class);
        assertThat(mapper.valueToTree(service.poll()).path("job").path("id").asText()).isEqualTo(first.toString());
        assertThat(service.cancelRun(first).get("state")).isEqualTo("CANCELLED");assertThat(service.cancelRun(first).get("state")).isEqualTo("CANCELLED");
        assertThatThrownBy(()->service.retryRun(first)).isInstanceOf(ConflictException.class);
        var page=mapper.createObjectNode().put("runId",first.toString()).put("pageId","late-page").put("cursor","").put("nextCursor","").put("completed",true).put("snapshotSequence",0).put("expectedDocumentCount",0);page.putArray("documents");
        assertThatThrownBy(()->service.page(page)).isInstanceOf(ConflictException.class);
        CompanyContext.setCompany(UUID.randomUUID());assertThatThrownBy(()->service.cancelRun(second)).isInstanceOf(NotFoundException.class);
    }
    @Test void transportBoundsCountUtf8BytesAndOversizePagesAreAtomic(){
        var oversized=snapshot(1,"EMPTY");oversized.put("sourceEvidence","я".repeat(4_000_001));
        assertThatThrownBy(()->service.receive(oversized)).isInstanceOf(BadRequestException.class).hasMessageContaining("size");
        UUID run=(UUID)service.createRun(mapper.createObjectNode().put("from","2025-02-22").put("to","2025-02-28").put("pageSize",10),"size-test").get("id");
        var page=mapper.createObjectNode().put("runId",run.toString()).put("pageId","large").put("cursor","").put("nextCursor","").put("completed",true).put("snapshotSequence",3).put("expectedDocumentCount",3);
        var docs=page.putArray("documents");for(int i=0;i<3;i++)docs.add(snapshot(1,"EMPTY").put("documentId","doc-"+i).put("evidence","a".repeat(5_400_000)));
        assertThatThrownBy(()->service.page(page)).isInstanceOf(BadRequestException.class).hasMessageContaining("size");
        assertThat(service.documents(10,0)).isEmpty();assertThat(service.historyRuns().getFirst().get("state")).isEqualTo("PENDING");
    }
}

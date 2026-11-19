package com.enterprise.mm.warehouse.history;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.error.*;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.service.InventoryScopeLockService;
import com.enterprise.mm.warehouse.history.WarehouseHistoryDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class WarehouseHistoryImportTest {
    String tenant="history-test",org="a".repeat(32);UUID company=UUID.randomUUID(),runId=UUID.randomUUID();
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();JdbcTemplate jdbc;TransactionTemplate tx;WarehouseHistoryImportService service;
    @BeforeEach void setUp() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__warehouse_source_history.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var sourceCompany=new Company();sourceCompany.setDescription("[1C:navoiyazot83:Catalog61:"+org+"]");
        var companies=mock(CompanyRepository.class);when(companies.findByTenantIdAndIdAndIsDeletedFalse(eq(tenant),eq(company))).thenReturn(Optional.of(sourceCompany));
        service=new WarehouseHistoryImportService(jdbc,mapper,Validation.buildDefaultValidatorFactory().getValidator(),companies,mock(EntityManager.class),mock(InventoryScopeLockService.class));
        TenantContext.setTenant(tenant);CompanyContext.setCompany(company);
        jdbc.execute("create table untouched_stock(id integer primary key,quantity decimal(22,7),book_value decimal(19,2))");
        jdbc.update("insert into untouched_stock values(1,1.0000007,123.45)");
    }
    @AfterEach void clear(){TenantContext.clear();CompanyContext.clear();}
    Row row(long seq,String kind,String key,String quantity) throws Exception {
        var data=mapper.createObjectNode().put("kind",kind).put("sourceKey",key).put("sourceTable","_document162").put("organizationSourceId",org)
            .put("documentType","Перемещение номенклатуры").put("documentKey","document:_document162:"+"1".repeat(32))
            .put("documentDate","2026-10-07 11:43:00").put("documentNumber","Источник-42").put("posted",true).put("marked",false);
        data.putObject("data").put("original","Exact source evidence");if(quantity!=null)data.put("quantity",quantity);
        String content=mapper.writeValueAsString(data);return new Row(seq,WarehouseHistoryImportService.sha(content),content);
    }
    String chain(List<Row> rows) throws Exception {String value="0".repeat(64);for(var row:rows)value=WarehouseHistoryImportService.sha(value+"\n"+mapper.readTree(row.content()).path("sourceKey").asText()+":"+row.sha256()+"\n");return value;}
    void start(List<Row> rows) throws Exception {
        var counts=new TreeMap<String,Long>();for(var r:rows)counts.merge(mapper.readTree(r.content()).path("kind").asText(),1L,Long::sum);
        var request=new Start(runId,"b".repeat(64),org,counts,chain(rows));tx.execute(s->service.start(tenant,company,request));
    }
    ChunkResult accept(List<Row> rows){return tx.execute(s->service.chunk(tenant,company,runId,new Chunk(rows)));}
    @Test void completeHistoryPreservesExactSignedDecimalsWithoutChangingStock() throws Exception {
        var rows=List.of(row(1,"DOCUMENT","document:one",null),row(2,"LINE","line:one","1.0000007"),row(3,"MOVEMENT","movement:one","-0.1234567"));
        start(rows);accept(rows);var run=tx.execute(s->service.complete(tenant,company,runId));
        assertThat(run.status()).isEqualTo("COMPLETED");assertThat(run.counts()).containsEntry("DOCUMENT",1L).containsEntry("LINE",1L).containsEntry("MOVEMENT",1L);
        assertThat(jdbc.queryForList("select quantity from warehouse_history_records where quantity is not null",String.class)).containsExactlyInAnyOrder("1.0000007","-0.1234567");
        assertThat(jdbc.queryForObject("select quantity from untouched_stock",java.math.BigDecimal.class)).isEqualByComparingTo("1.0000007");
        assertThat(jdbc.queryForObject("select book_value from untouched_stock",java.math.BigDecimal.class)).isEqualByComparingTo("123.45");
    }
    @Test void replayAfterCompletionReturnsSameChunkWithoutDuplicateRows() throws Exception {
        var rows=List.of(row(1,"DOCUMENT","document:one",null));start(rows);var first=accept(rows);tx.execute(s->service.complete(tenant,company,runId));
        assertThat(accept(rows)).isEqualTo(first);assertThat(jdbc.queryForObject("select count(*) from warehouse_history_records",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->accept(List.of(row(1,"DOCUMENT","document:changed",null)))).isInstanceOf(ConflictException.class);
    }
    @Test void badHashAndSequenceLeaveTheWholeChunkUnwritten() throws Exception {
        var good=row(1,"DOCUMENT","document:one",null);var bad=row(2,"LINE","line:one","1.0000007");start(List.of(good,bad));
        assertThatThrownBy(()->accept(List.of(good,new Row(2,"f".repeat(64),bad.content())))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->accept(List.of(bad))).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_history_records",Long.class)).isZero();
    }
    @Test void duplicateSourceIdentityRollsBackAnEntireLaterChunk() throws Exception {
        var one=row(1,"DOCUMENT","document:one",null);var two=row(2,"DOCUMENT","document:two",null);start(List.of(one,two));accept(List.of(one));
        assertThatThrownBy(()->accept(List.of(row(2,"DOCUMENT","document:one",null)))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.status(tenant,company,runId).acceptedRecords()).isEqualTo(1);
    }
    @Test void wrongScopeOrganizationAndInexactNumbersAreRejected() throws Exception {
        var good=row(1,"DOCUMENT","document:one",null);start(List.of(good));
        assertThatThrownBy(()->service.status(tenant,UUID.randomUUID(),runId)).isInstanceOf(BadRequestException.class);
        var data=mapper.readTree(good.content()).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)data).put("organizationSourceId","f".repeat(32));
        String content=mapper.writeValueAsString(data);
        assertThatThrownBy(()->accept(List.of(new Row(1,WarehouseHistoryImportService.sha(content),content)))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->accept(List.of(row(1,"DOCUMENT","document:one","1e-7")))).isInstanceOf(BadRequestException.class);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        assertThatThrownBy(()->service.status(tenant,CompanyContext.DEFAULT_COMPANY_ID,runId)).isInstanceOf(BadRequestException.class);
    }
    @Test void completionRequiresAllExpectedRowsAndMatchingFullSourceChain() throws Exception {
        var one=row(1,"DOCUMENT","document:one",null);var two=row(2,"LINE","line:two","0.0000001");start(List.of(one,two));accept(List.of(one));
        assertThatThrownBy(()->tx.execute(s->service.complete(tenant,company,runId))).isInstanceOf(ConflictException.class);
        accept(List.of(two));jdbc.update("update warehouse_history_runs set chain_sha256=? where id=?","c".repeat(64),runId);
        assertThatThrownBy(()->tx.execute(s->service.complete(tenant,company,runId))).isInstanceOf(ConflictException.class);
    }
    @Test void storedProofRehashesContentsAndDetectsDatabaseTampering() throws Exception {
        var rows=List.of(row(1,"DOCUMENT","document:one",null),row(2,"LINE","line:one","0.0000001"));start(rows);accept(rows);
        assertThat(service.proof(tenant,company,runId,0,"0".repeat(64))).containsEntry("next",2L).containsEntry("chainSha256",chain(rows));
        jdbc.update("update warehouse_history_records set content=content||' ' where source_seq=2");
        assertThatThrownBy(()->service.proof(tenant,company,runId,0,"0".repeat(64))).isInstanceOf(ConflictException.class);
    }
    @Test void sourceDatesKeepTheirOriginalYearAndOversizedManifestIsRejected() throws Exception {
        var r=row(1,"DOCUMENT","document:old",null);String content=r.content().replace("2026-10-07","0074-10-07");
        var old=new Row(1,WarehouseHistoryImportService.sha(content),content);start(List.of(old));accept(List.of(old));
        assertThat(jdbc.queryForObject("select document_date from warehouse_history_records",java.time.LocalDateTime.class).getYear()).isEqualTo(74);
        assertThatThrownBy(()->service.start(tenant,company,new Start(UUID.randomUUID(),"a".repeat(64),org,Map.of("DOCUMENT",Long.MAX_VALUE),"0".repeat(64)))).isInstanceOf(BadRequestException.class);
    }
}

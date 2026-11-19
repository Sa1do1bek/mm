package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.*;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.RoleDataScopeService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.testcontainers.junit.jupiter.Testcontainers
class ImportedStockMovementTest {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres=new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");
    final UUID company=UUID.randomUUID(),other=UUID.randomUUID(),material=UUID.randomUUID(),warehouse=UUID.randomUUID(),target=UUID.randomUUID(),run=UUID.randomUUID(),normal=UUID.randomUUID();
    JdbcTemplate jdbc;ImportedStockMovementQuery query;RoleDataScopeService roles;
    @BeforeEach void setup() throws Exception {
        TenantContext.setTenant("history-test");CompanyContext.setCompany(company);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        String baseline=Files.readString(Path.of("src/main/resources/db/migration/V1__baseline_schema.sql"));
        int functionStart=baseline.indexOf("CREATE FUNCTION public.multilingual_name_value");
        jdbc.execute(baseline.substring(functionStart,baseline.indexOf("$$;",functionStart)+3));
        jdbc.execute("create table materials(id uuid,tenant_id text,company_id uuid,name text,code text,unit_of_measure_id uuid)");
        jdbc.execute("create table warehouses(id uuid,tenant_id text,company_id uuid,name text,code text)");
        jdbc.execute("create table unit_of_measures(id uuid,tenant_id text,code text,measurement_name_short text)");
        jdbc.execute("create table stock_movements(id uuid,tenant_id text,company_id uuid,material_id uuid,warehouse_id uuid,target_warehouse_id uuid,movement_date date,created_at timestamp,code text,name text,description text,status text,reference_id uuid,reference_type text,movement_type text,source_inventory_item_id uuid,is_delete boolean,quantity numeric default 0)");
        jdbc.execute("create table stock_balances(id uuid,tenant_id text,company_id uuid,quantity numeric,reserved_quantity numeric,total_value numeric)");
        jdbc.update("insert into stock_balances values(?,?,?,?,?,?)",UUID.randomUUID(),"history-test",company,new java.math.BigDecimal("123.0000001"),new java.math.BigDecimal("4"),new java.math.BigDecimal("456.0001"));
        jdbc.update("insert into materials values(?,?,?,?,?,null)",material,"history-test",company,"Copper","CU");
        jdbc.update("insert into warehouses values(?,?,?,?,?)",warehouse,"history-test",company,"Central","WH-1");
        jdbc.update("insert into warehouses values(?,?,?,?,?)",target,"history-test",company,"Workshop","WH-2");
        jdbc.update("insert into stock_movements values(?,?,?,?,?,?,?,?,'MM-1','MM receipt',null,'POSTED',null,null,'RECEIPT',null,false,0)",normal,"history-test",company,material,warehouse,null,LocalDate.parse("2026-10-09"),LocalDateTime.parse("2026-10-09T12:00:00"));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V8__warehouse_source_history.sql")));
        jdbc.update("insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,expected_chain,chain_sha256,status) values(?,?,?,?,?,'{}',0,?,?,'COMPLETED')",run,"history-test",company,"a".repeat(64),"b".repeat(32),"c".repeat(64),"c".repeat(64));
        leg("receipt","2026-09-01",warehouse,"3.0000001","30.0001",true,"1010","DT",material);
        leg("receipt","2026-09-01",warehouse,"2","20",true,"1020","DT",material);
        leg("reclassification","2026-09-02",warehouse,"2","20",true,"1010","DT",material);
        leg("reclassification","2026-09-02",warehouse,"-2","-20",true,"1020","CT",material);
        leg("Перемещение","2026-09-03",warehouse,"-2","-20",true,"1010","CT",material);
        leg("Перемещение","2026-09-03",target,"2","20",true,"1010","DT",material);
        leg("inactive","2026-09-04",warehouse,"99","990",false,"1010","DT",material);
        leg("custody","2026-09-04",warehouse,"99","990",true,"0020","DT",material);
        leg("legacy receipt","2026-09-05",null,"1","10",true,"1010","DT",null);
        leg("legacy issue","2026-09-06",null,"-1","-10",true,"1010","CT",null);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V9__warehouse_imported_movement_projection.sql")));
        roles=mock(RoleDataScopeService.class);when(roles.applyWarehouseScope(any())).thenAnswer(a->a.getArgument(0));
        query=new ImportedStockMovementQuery(new NamedParameterJdbcTemplate(jdbc),roles);
    }
    void leg(String doc,String date,UUID wh,String qty,String value,boolean active,String account,String direction,UUID mat) throws Exception {
        long seq=jdbc.queryForObject("select count(*)+1 from warehouse_history_records",Long.class);
        String sourceWh=wh==null?"archived-warehouse":wh.toString(),sourceMat=mat==null?"archived-material":mat.toString();
        String body=new ObjectMapper().writeValueAsString(Map.of("materialSourceId",sourceMat,"warehouseSourceId",sourceWh,"data",Map.of("accountCode",account)));
        jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,document_key,document_type,document_date,posted,material_id,warehouse_id,quantity,book_value,direction,row_sha256,content) values(?,?,?,?,?,'MOVEMENT',?,'_accrg4272',?,?,?, ?,?,?,?,?,?,?,?)",UUID.randomUUID(),"history-test",company,run,seq,"leg:"+seq,"doc:"+doc,doc,LocalDateTime.parse(date+"T12:00:00"),active,mat,wh,qty,value,direction,"d".repeat(64),body);
        if(jdbc.queryForObject("select count(*) from warehouse_history_records where document_key=? and kind='DOCUMENT'",Integer.class,"doc:"+doc)==0)
            jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,document_key,document_type,document_number,row_sha256,content) values(?,?,?,?,?,'DOCUMENT',?,'_document162',?,?,?,'hash','{}')",UUID.randomUUID(),"history-test",company,run,seq+1000,"doc:"+doc,"doc:"+doc,doc,"DOC-"+seq);
    }
    @AfterEach void cleanup(){jdbc.execute("drop schema public cascade");jdbc.execute("create schema public");CompanyContext.clear();TenantContext.clear();}
    @Test void physicalProjectionNetsOverlappingLegsWithoutChangingStockReservationsOrPostings() {
        assertThat(jdbc.queryForObject("select count(*) from warehouse_imported_movements",Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select quantity from warehouse_imported_movements where document_key='doc:receipt'",java.math.BigDecimal.class)).isEqualByComparingTo("5.0000001");
        assertThat(jdbc.queryForObject("select count(*) from warehouse_imported_movements where document_key in ('doc:reclassification','doc:inactive','doc:custody')",Long.class)).isZero();
        var state=jdbc.queryForList("select stock_state::text from warehouse_history_projection_state order by stage");
        assertThat(state).hasSize(2);assertThat(state.get(0)).isEqualTo(state.get(1));
        assertThat(jdbc.queryForObject("select count(*) from stock_movements",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_history_records where kind='MOVEMENT'",Long.class)).isEqualTo(10);
        var verification=new WarehouseHistoryProjectionVerification(new NamedParameterJdbcTemplate(jdbc)).verify();
        var projection=(Map<?,?>)verification.get("projection");
        assertThat(projection.get("movements")).isEqualTo(5L);
        assertThat(projection.get("semantic_md5").toString()).matches("[a-f0-9]{32}");
    }
    @Test void normalListCombinesMmAndHistoricMovementsAndUsesEffectiveDates() {
        StockMovementResponse current=new StockMovementResponse();current.setId(normal);
        var page=query.search(StockMovementSearchRequest.empty(),PageRequest.of(0,20),id->current);
        assertThat(page.getTotalElements()).isEqualTo(6);assertThat(page.getContent().getFirst().getId()).isEqualTo(normal);
        assertThat(page.getContent().stream().filter(StockMovementResponse::isHistorical)).hasSize(5);
        var filter=new StockMovementSearchRequest(null,null,warehouse,List.of(material),LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-04"),false,null,null,null,null);
        var historic=query.search(filter,PageRequest.of(0,20),id->{throw new AssertionError("No modern movement belongs to this period");});
        assertThat(historic.getTotalElements()).isEqualTo(3);
        assertThat(historic.getContent().stream().filter(r->r.getMovementType()==com.enterprise.mm.enums.MovementType.TRANSFER)).hasSize(2);
        var receipt=historic.getContent().stream().filter(r->r.getMovementType()==com.enterprise.mm.enums.MovementType.RECEIPT).findFirst().orElseThrow();
        assertThat(receipt.getQuantity()).isEqualByComparingTo("5.0000001");assertThat(receipt.getStockEffects()).isFalse();
        assertThatThrownBy(()->query.assertOperational(receipt.getId())).isInstanceOf(BusinessException.class);
        query.assertOperational(normal);
    }
    @Test void movementSearchFindsMaterialNamesCodesAndManualSourceReferences() {
        jdbc.update("update stock_movements set description='Workshop return REF_50%! where recorded' where id=?",normal);
        StockMovementResponse current=new StockMovementResponse();current.setId(normal);
        for(String search:List.of("Copper","CU")) {
            var request=new StockMovementSearchRequest(search,null,warehouse,null,null,null,false,null,null,null,null);
            assertThat(query.search(request,PageRequest.of(0,20),id->current).getTotalElements()).isEqualTo(4);
        }
        for(String search:List.of("Workshop return","REF_50%!")) {
            var request=new StockMovementSearchRequest(search,null,warehouse,null,null,null,false,null,null,null,null);
            assertThat(query.search(request,PageRequest.of(0,20),id->current).getContent()).singleElement().extracting(StockMovementResponse::getId).isEqualTo(normal);
        }
    }
    @Test void companiesAndWarehouseAssignmentsApplyToHistoricDetailsAndLists() {
        UUID id=jdbc.queryForObject("select id from warehouse_imported_movements where document_key='doc:receipt'",UUID.class);
        CompanyContext.setCompany(other);assertThat(query.available()).isFalse();assertThat(query.detail(id)).isEmpty();
        CompanyContext.setCompany(company);when(roles.effectiveWarehouseId(null)).thenReturn(target);
        assertThat(query.detail(id)).isEmpty();
        when(roles.applyWarehouseScope(any())).thenAnswer(a->SearchCriteria.withWarehouseId(a.getArgument(0),target));
        assertThat(query.search(StockMovementSearchRequest.empty(),PageRequest.of(0,20),x->{throw new AssertionError();}).getTotalElements()).isEqualTo(2);
    }
    @Test void publicSortsDatesAndBatchedLoadingWorkForMixedSources() {
        jdbc.update("update stock_movements set movement_date=null,quantity=100 where id=?",normal);
        StockMovementResponse current = new StockMovementResponse(); current.setId(normal); current.setQuantity(new java.math.BigDecimal("100"));
        for(String field:com.enterprise.mm.warehouse.movements.MovementQuerySupport.SQL_SORTS.keySet()) for(String direction:List.of("asc","desc")) {
            var sort=com.enterprise.mm.warehouse.movements.MovementQuerySupport.sort(List.of(field+","+direction));
            assertThat(query.searchBatch(StockMovementSearchRequest.empty(),PageRequest.of(0,20,sort),ids->{
                assertThat(ids).containsExactly(normal);return List.of(current);
            }).getTotalElements()).isEqualTo(6);
        }
        var date=new StockMovementSearchRequest(null,null,null,null,LocalDate.parse("2026-10-09"),LocalDate.parse("2026-10-09"),false,null,null,null,null);
        assertThat(query.search(date,PageRequest.of(0,20),id->current).getContent()).extracting(StockMovementResponse::getId).containsExactly(normal);
        var sort=com.enterprise.mm.warehouse.movements.MovementQuerySupport.sort(List.of("quantity,desc"));
        assertThat(query.search(StockMovementSearchRequest.empty(),PageRequest.of(0,20,sort),id->current).getContent().getFirst().getId()).isEqualTo(normal);
        // Both the single-value filter and the multi-value filter must apply, as in JPA.
        var conflict=new StockMovementSearchRequest(null,null,null,null,null,null,false,null,null,null,
            com.enterprise.mm.enums.MovementType.RECEIPT,null,null,null,null,null,List.of(com.enterprise.mm.enums.MovementType.TRANSFER));
        assertThat(query.search(conflict,PageRequest.of(0,20),id->current).getTotalElements()).isZero();
        List<UUID> pagedIds=new ArrayList<>();
        for(int page=0;page<3;page++) pagedIds.addAll(query.search(StockMovementSearchRequest.empty(),PageRequest.of(page,2,sort),id->current).getContent().stream().map(StockMovementResponse::getId).toList());
        assertThat(pagedIds).hasSize(6).doesNotHaveDuplicates();
    }

    @Test void mixedMovementExportMatchesListOrderAndRowCountInAllLanguages() throws Exception {
        jdbc.update("update materials set name=? where id=?","ru: Медь\nen: Copper\nuz: Mis",material);
        var current=new StockMovementResponse();current.setId(normal);current.setCode("MM-1");
        current.setMovementDate(LocalDate.parse("2026-10-09"));current.setMaterialName("ru: Медь\nen: Copper\nuz: Mis");
        current.setQuantity(java.math.BigDecimal.ONE);
        var operational=mock(com.enterprise.mm.service.StockMovementService.class, CALLS_REAL_METHODS);
        doReturn(List.of(current)).when(operational).getQueryRows(any());
        var exporter=operational;
        org.springframework.test.util.ReflectionTestUtils.setField(exporter,"importedMovements",query);
        org.springframework.test.util.ReflectionTestUtils.setField(exporter,"jdbcTemplate",new NamedParameterJdbcTemplate(jdbc));
        org.springframework.test.util.ReflectionTestUtils.setField(exporter,"roleDataScopeService",roles);
        org.springframework.test.util.ReflectionTestUtils.setField(exporter,"maxRows",1000);
        var sort=com.enterprise.mm.warehouse.movements.MovementQuerySupport.sort(List.of("code,asc"));
        var expected=query.search(StockMovementSearchRequest.empty(),PageRequest.of(0,20,sort),id->current).getContent();
        for(String language:List.of("uz","ru","en")) {
            try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(exporter.exportMovements(StockMovementSearchRequest.empty(),sort,language)))) {
                var sheet=book.getSheetAt(0);
                assertThat(sheet.getLastRowNum()).isEqualTo(expected.size());
                for(int index=0;index<expected.size();index++) assertThat(sheet.getRow(index+1).getCell(1).getStringCellValue()).isEqualTo(expected.get(index).getCode());
                assertThat(sheet.getRow(1).getCell(7).getStringCellValue()).isEqualTo(switch(language){case "uz"->"Mis";case "ru"->"Медь";default->"Copper";});
            }
        }
    }

}

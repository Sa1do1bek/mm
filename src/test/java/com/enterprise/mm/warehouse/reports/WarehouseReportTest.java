package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.InventoryAccountingSettingsService;
import com.enterprise.mm.service.RoleDataScopeService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static com.enterprise.mm.warehouse.reports.WarehouseReport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.testcontainers.junit.jupiter.Testcontainers
class WarehouseReportTest {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres = new org.testcontainers.containers.PostgreSQLContainer<>("postgres:17-alpine");
    private final UUID company=UUID.randomUUID(),warehouse=UUID.randomUUID(),target=UUID.randomUUID(),material=UUID.randomUUID(),pcs=UUID.randomUUID(),group=UUID.randomUUID();
    private JdbcTemplate jdbc;
    private WarehouseReportQuery query;
    private WarehouseReportService service;
    private RoleDataScopeService access;

    @BeforeEach void setup() {
        TenantContext.setTenant("reports-test"); CompanyContext.setCompany(company);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        for(String sql:List.of(
            "create table companies(id uuid,tenant_id varchar,name varchar,legal_name varchar)",
            "create table materials(id uuid,tenant_id varchar,company_id uuid,code varchar,name varchar,material_group_id uuid,unit_of_measure_id uuid,material_type varchar default 'TMB',material_subtype varchar)",
            "create table warehouses(id uuid,tenant_id varchar,company_id uuid,code varchar,name varchar,is_delete boolean default false)",
            "create table material_groups(id uuid,tenant_id varchar,company_id uuid,code varchar,name varchar)",
            "create table unit_of_measures(id uuid,tenant_id varchar,code varchar,measurement_name_short varchar,measurement_name_short_ru varchar,measurement_name_short_uz varchar,measurement_name_long varchar,measurement_name_long_ru varchar,measurement_name_long_uz varchar)",
            "create table stock_movements(id uuid,tenant_id varchar,company_id uuid,material_id uuid,warehouse_id uuid,target_warehouse_id uuid,movement_date date,posted_at timestamp,status varchar,reference_type varchar,reference_id uuid,movement_type varchar,quantity decimal(22,7),total_cost decimal(19,4),reversal_of_id uuid,account_code_snapshot varchar)",
            "create table stock_issue_account_reviews(movement_id uuid,tenant_id varchar,company_id uuid,status varchar,stock_account_code varchar,counter_account_code varchar,reviewed_at timestamp)",
            "create table stock_movement_allocations(movement_id uuid,tenant_id varchar,company_id uuid,material_id uuid,source_warehouse_id_snapshot uuid,target_warehouse_id_snapshot uuid,on_hand_delta decimal(22,7),total_value_delta decimal(19,4))",
            "create table stock_cost_movements(id uuid,tenant_id varchar,company_id uuid,material_id uuid,warehouse_id uuid,created_at timestamp,source_document_type varchar,source_document_id uuid,movement_type varchar,total_cost decimal(19,4),is_delete boolean,status varchar,stock_movement_id uuid)",
            "create table stock_balances(material_id uuid,warehouse_id uuid,tenant_id varchar,company_id uuid,quantity decimal(22,7),total_value decimal(19,4),is_delete boolean)",
            "create table exact_opening_imports(tenant_id varchar,company_id uuid,posting_date date,status varchar)",
            "create table goods_receipts(id uuid,tenant_id varchar,company_id uuid,supplier_id uuid)",
            "create table purchase_orders(id uuid,tenant_id varchar,company_id uuid,supplier_id uuid)",
            "create table suppliers(id uuid,tenant_id varchar,company_id uuid,name varchar)",
            "create table issue_requests(id uuid,tenant_id varchar,company_id uuid,receiver_user_id uuid)",
            "create table users(id uuid,tenant_id varchar,full_name varchar)",
            "create table warehouse_imported_movements(id uuid,tenant_id varchar,company_id uuid,document_key varchar,movement_date date,source_record_id uuid,target_warehouse_id uuid,movement_type varchar,quantity numeric,amount numeric,material_id uuid,warehouse_id uuid,material_source_id varchar,warehouse_source_id varchar,material_name varchar,warehouse_name varchar,material_code varchar,warehouse_code varchar)"
        )) jdbc.execute(sql);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource("db/migration/V15__warehouse_report_approvals.sql"))
            .execute(jdbc.getDataSource());
        jdbc.update("insert into companies values(?,?,?,?)",company,"reports-test","Navoiyazot","Navoiyazot JSC");
        jdbc.update("insert into warehouses values(?,?,?,?,?)",warehouse,"reports-test",company,"01","Central warehouse");
        jdbc.update("insert into warehouses values(?,?,?,?,?)",target,"reports-test",company,"02","Workshop warehouse");
        jdbc.update("insert into material_groups values(?,?,?,?,?)",group,"reports-test",company,"10","ru: Металлы\nen: Metals\nuz: Metallar");
        UUID kg=UUID.randomUUID(),pieces=UUID.randomUUID();
        jdbc.update("insert into unit_of_measures(id,tenant_id,code) values(?,?,?)",kg,"reports-test","kg");
        jdbc.update("insert into unit_of_measures(id,tenant_id,code) values(?,?,?)",pieces,"reports-test","pcs");
        jdbc.update("insert into materials(id,tenant_id,company_id,code,name,material_group_id,unit_of_measure_id) values(?,?,?,?,?,?,?)",material,"reports-test",company,"000001","Copper 100%",group,kg);
        jdbc.update("insert into materials(id,tenant_id,company_id,code,name,material_group_id,unit_of_measure_id) values(?,?,?,?,?,?,?)",pcs,"reports-test",company,"000002","Pump",group,pieces);
        jdbc.update("insert into exact_opening_imports values(?,?,?,?)","reports-test",company,LocalDate.of(2026,10,1),"COMPLETED");
        var named=new NamedParameterJdbcTemplate(jdbc); query=new WarehouseReportQuery(named);
        access=mock(RoleDataScopeService.class); when(access.warehouseReadScope(any(),any())).thenAnswer(call -> call.getArgument(0)==null ? java.util.Set.of() : java.util.Set.of((java.util.UUID)call.getArgument(0)));
        var settings=mock(InventoryAccountingSettingsService.class); when(settings.findOrDefault()).thenReturn(new InventoryAccountingSettings());
        service=new WarehouseReportService(query,access,settings,named);
    }

    @Test void departmentWarehouseScopeFiltersRowsTotalsReferencesAndMaterialCard() {
        UUID outside=UUID.randomUUID();
        jdbc.update("insert into warehouses values(?,?,?,?,?)",outside,"reports-test",company,"OUTSIDE","Other department");
        for(UUID wh:List.of(warehouse,target,outside)) {
            post(material,wh,"2026-10-02","RECEIPT","10","100",null,"POSTED",true);
            jdbc.update("insert into stock_balances values(?,?,?,?,?,?,false)",material,wh,"reports-test",company,BigDecimal.TEN,new BigDecimal("100"));
        }
        when(access.warehouseReadScope(any(),any())).thenReturn(java.util.Set.of(warehouse,target));
        var filter=new Filter(Type.STOCK_BALANCES,LocalDate.of(2026,10,2),LocalDate.of(2026,10,2),null,null,null,null,0,50);
        var report=service.generate(filter,false);
        assertThat(report.content()).extracting(Row::warehouseId).containsExactlyInAnyOrder(warehouse,target);
        assertThat(report.totals()).singleElement().satisfies(total -> assertThat(new BigDecimal(total.closingQuantity())).isEqualByComparingTo("20"));
        var card=service.materialCard(material,null,filter.from(),filter.to(),null,0,50);
        assertThat(new BigDecimal(card.closingQuantity())).isEqualByComparingTo("20");
        assertThat(service.references("WAREHOUSE",filter).content()).extracting(row -> row.get("id")).containsExactlyInAnyOrder(warehouse,target);
    }

    @AfterEach void clean() { jdbc.execute("drop schema public cascade"); jdbc.execute("create schema public"); CompanyContext.clear(); TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private UUID post(UUID mat,UUID wh,String date,String type,String quantity,String amount,String ref,String status,boolean allocations) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into stock_movements(id,tenant_id,company_id,material_id,warehouse_id,target_warehouse_id,movement_date,posted_at,status,reference_type,reference_id,movement_type,quantity,total_cost,reversal_of_id) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,"reports-test",company,mat,wh,null,LocalDate.parse(date),LocalDateTime.parse(date+"T12:00:00"),status,ref,null,type,new BigDecimal(quantity).abs(),new BigDecimal(amount).abs(),null);
        if(allocations) allocation(id,mat,wh,quantity,amount);
        return id;
    }

    private void allocation(UUID id,UUID mat,UUID wh,String quantity,String amount) {
        boolean source=new BigDecimal(quantity).signum()<0;
        jdbc.update("insert into stock_movement_allocations values(?,?,?,?,?,?,?,?)",id,"reports-test",company,mat,source?wh:null,source?null:wh,new BigDecimal(quantity),new BigDecimal(amount));
    }
    private Filter filter(Type type,String from,String to) { return new Filter(type,LocalDate.parse(from),LocalDate.parse(to),null,null,null,null,0,50); }
    private Row row(List<Row> rows,UUID wh) { return rows.stream().filter(r->r.warehouseId().equals(wh) && r.materialId().equals(material)).findFirst().orElseThrow(); }
    private void amount(String actual,String expected) { assertThat(new BigDecimal(actual)).isEqualByComparingTo(expected); }

    private void history(UUID mat,UUID wh,String date,String quantity,String value,String type) {
        jdbc.update("insert into warehouse_imported_movements(id,tenant_id,company_id,document_key,movement_date,source_record_id,movement_type,quantity,amount,material_id,warehouse_id,material_source_id,warehouse_source_id,material_name,warehouse_name,material_code,warehouse_code) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),"reports-test",company,"document:test",LocalDate.parse(date),UUID.randomUUID(),type,new BigDecimal(quantity),new BigDecimal(value),mat,wh,"source-material","source-warehouse","Archived copper","Archived warehouse","OLD-01","OLD-WH");
    }

    @Test void companyWideOpeningReportCompletesAfterStatisticsMigrationAtImportedWarehouseScale() throws Exception {
        // Match the imported opening's cardinality and the production planner's memory/cost settings.
        for(String table:List.of("materials","warehouses","material_groups","unit_of_measures","stock_movements",
            "goods_receipts","purchase_orders","suppliers","issue_requests","users"))
            jdbc.execute("alter table "+table+" add primary key(id)");
        jdbc.execute("create index allocations_scope_movement on stock_movement_allocations(tenant_id,company_id,movement_id)");
        jdbc.update("""
            insert into materials(id,tenant_id,company_id,code,name,material_group_id,unit_of_measure_id)
            select md5('scale-material:'||n)::uuid,tenant_id,company_id,'S'||n,'Scale material '||n,material_group_id,unit_of_measure_id
            from materials cross join generate_series(1,44276) n where id=?
            """,material);
        jdbc.update("""
            insert into stock_movements(id,tenant_id,company_id,material_id,warehouse_id,target_warehouse_id,movement_date,posted_at,status,reference_type,reference_id,movement_type,quantity,total_cost,reversal_of_id)
            select md5('scale-movement:'||code)::uuid,tenant_id,company_id,id,?,null,date '2026-10-01',
              timestamp '2026-10-01 12:00:00','POSTED','OPENING_STOCK_EXACT',null,'RECEIPT',1.0000001,10.0123,null
            from materials where code like 'S%'
            """,warehouse);
        jdbc.execute("""
            insert into stock_movement_allocations
            select id,tenant_id,company_id,material_id,null,warehouse_id,quantity,total_cost from stock_movements
            """);
        jdbc.execute("""
            insert into stock_balances
            select material_id,warehouse_id,tenant_id,company_id,quantity,total_cost,false from stock_movements
            """);
        jdbc.execute("alter role "+postgres.getUsername()+" set work_mem='1048kB'");
        jdbc.execute("alter role "+postgres.getUsername()+" set random_page_cost=1.1");
        jdbc.execute("alter role "+postgres.getUsername()+" set statement_timeout='15s'");
        try {
            // The bulk opening may complete before autovacuum collects statistics. Apply the
            // release's actual maintenance migration; it must not change any stock values.
            String stockBefore=jdbc.queryForObject("select md5(string_agg(row_to_json(s)::text,'' order by material_id)) from stock_balances s",String.class);
            String maintenance=new org.springframework.core.io.ClassPathResource("db/migration/V11__warehouse_report_statistics.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status->jdbc.execute(maintenance));
            long started=System.nanoTime();
            var report=service.generate(new Filter(Type.STOCK_BALANCES,LocalDate.of(2026,10,2),LocalDate.of(2026,10,2),null,null,null,null,0,1),false);
            System.out.println("Opening-scale report milliseconds="+(System.nanoTime()-started)/1_000_000);
            assertThat(report.totalElements()).isEqualTo(44276);
            assertThat(report.content()).hasSize(1);
            assertThat(report.warnings()).doesNotContain("LEDGER_DIFFERS_FROM_CURRENT_BALANCES");
            assertThat(report.totals()).hasSize(1);
            amount(report.totals().getFirst().closingQuantity(),"44276.0044276");
            amount(report.totals().getFirst().closingAmount(),"443304.5948");
            assertThat(jdbc.queryForObject("select md5(string_agg(row_to_json(s)::text,'' order by material_id)) from stock_balances s",String.class)).isEqualTo(stockBefore);
        } finally {
            jdbc.execute("alter role "+postgres.getUsername()+" reset statement_timeout");
            jdbc.execute("alter role "+postgres.getUsername()+" reset work_mem");
            jdbc.execute("alter role "+postgres.getUsername()+" reset random_page_cost");
        }
    }
    @Test void historicalPeriodsCrossTheSnapshotWithoutCountingTheOpeningTwice() {
        post(material,warehouse,"2026-10-01","RECEIPT","100.0000001","1000.0123","OPENING_STOCK_EXACT","POSTED",true);
        history(material,warehouse,"2026-09-01","120.0000001","1200.0123","RECEIPT");
        history(material,warehouse,"2026-09-10","-20","-200","ISSUE");
        post(material,warehouse,"2026-10-03","ISSUE","-4","-40",null,"POSTED",true);
        jdbc.update("insert into stock_balances values(?,?,?,?,?,?,false)",material,warehouse,"reports-test",company,new BigDecimal("96.0000001"),new BigDecimal("960.0123"));
        String before=jdbc.queryForObject("select row_to_json(s)::text from stock_balances s",String.class);
        var past=service.generate(filter(Type.MATERIAL_STATEMENT,"2026-09-01","2026-09-15"),false);
        amount(row(past.content(),warehouse).openingQuantity(),"0");
        amount(row(past.content(),warehouse).receiptQuantity(),"120.0000001");
        amount(row(past.content(),warehouse).issueQuantity(),"20");
        amount(row(past.content(),warehouse).closingQuantity(),"100.0000001");
        var crossing=service.generate(filter(Type.MATERIAL_STATEMENT,"2026-09-05","2026-10-05"),false);
        amount(row(crossing.content(),warehouse).openingQuantity(),"120.0000001");
        amount(row(crossing.content(),warehouse).issueQuantity(),"24");
        amount(row(crossing.content(),warehouse).closingQuantity(),"96.0000001");
        var current=service.generate(filter(Type.STOCK_BALANCES,"2026-10-05","2026-10-05"),false);
        amount(row(current.content(),warehouse).closingAmount(),"960.0123");
        assertThat(current.warnings()).contains("ONE_C_HISTORY_INTEGRATED").doesNotContain("LEDGER_DIFFERS_FROM_CURRENT_BALANCES");
        assertThat(service.options().coverageStart()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(jdbc.queryForObject("select row_to_json(s)::text from stock_balances s",String.class)).isEqualTo(before);
        amount(row(service.generate(filter(Type.RECEIPTS,"2026-09-01","2026-09-30"),false).content(),warehouse).receiptQuantity(),"120.0000001");
    }

    @Test void legacyReferencesKeepHistoricalActivityAndDoNotCreateCurrentStock() {
        history(null,null,"2026-09-01","7.0000001","70.0001","RECEIPT");
        history(null,null,"2026-09-10","-7.0000001","-70.0001","ISSUE");
        var past=service.generate(filter(Type.MATERIAL_STATEMENT,"2026-09-01","2026-09-05"),false);
        assertThat(past.content()).hasSize(1);
        amount(past.content().getFirst().closingQuantity(),"7.0000001");
        assertThat(past.content().getFirst().materialName()).isEqualTo("Archived copper");
        assertThat(past.content().getFirst().uom()).isEqualTo("1C-UNKNOWN-source-material");
        assertThat(service.generate(filter(Type.STOCK_BALANCES,"2026-10-05","2026-10-05"),false).content()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from stock_balances",Long.class)).isZero();
    }

    @Test void openingSevenPlaceValuesSplitIssuesAndDatedReversalPreserveAccountingEquation() {
        post(material,warehouse,"2026-10-01","RECEIPT","100.0000001","1000.0123","OPENING_STOCK_EXACT","POSTED",true);
        post(material,warehouse,"2026-10-03","RECEIPT","20","200",null,"POSTED",true);
        UUID issue=post(material,warehouse,"2026-10-04","ISSUE","-4","-40",null,"CANCELLED",true);
        allocation(issue,material,warehouse,"-6","-60");
        UUID reverse=post(material,warehouse,"2026-10-06","REVERSAL","10","100","REVERSAL","POSTED",true);
        jdbc.update("update stock_movements set reversal_of_id=? where id=?",issue,reverse);
        post(material,warehouse,"2026-10-04","RECEIPT","9000","9000",null,"DRAFT",true);
        var before=query.rows(filter(Type.MATERIAL_STATEMENT,"2026-10-01","2026-10-05"),true).getFirst();
        amount(before.openingQuantity(),"100.0000001"); amount(before.receiptQuantity(),"20"); amount(before.issueQuantity(),"10"); amount(before.closingQuantity(),"110.0000001");
        amount(before.closingAmount(),"1100.0123");
        var after=query.rows(filter(Type.MATERIAL_STATEMENT,"2026-10-01","2026-10-06"),true).getFirst();
        amount(after.receiptQuantity(),"20"); amount(after.issueQuantity(),"0"); amount(after.closingQuantity(),"120.0000001");
        var reversalOnly=query.rows(filter(Type.ISSUES,"2026-10-06","2026-10-06"),true).getFirst();
        amount(reversalOnly.issueQuantity(),"-10"); amount(reversalOnly.issueAmount(),"-100");
        assertThat(query.rows(filter(Type.RECEIPTS,"2026-10-01","2026-10-01"),true)).isEmpty();
    }

    @Test void transfersHaveTwoWarehouseLegsButLocalRelocationsHaveNoTurnover() {
        post(material,warehouse,"2026-10-01","RECEIPT","100","1000","OPENING_STOCK_EXACT","POSTED",true);
        UUID transfer=post(material,warehouse,"2026-10-02","TRANSFER","-5","-50",null,"POSTED",true);
        jdbc.update("update stock_movements set target_warehouse_id=? where id=?",target,transfer);
        allocation(transfer,material,target,"5","50");
        UUID relocation=post(material,warehouse,"2026-10-03","TRANSFER","-2","-20",null,"POSTED",true);
        allocation(relocation,material,warehouse,"2","20");
        var rows=query.rows(filter(Type.MATERIAL_STATEMENT,"2026-10-01","2026-10-03"),true);
        amount(row(rows,warehouse).issueQuantity(),"5"); amount(row(rows,warehouse).closingQuantity(),"95");
        amount(row(rows,target).receiptQuantity(),"5");
        assertThat(query.rows(filter(Type.RECEIPTS,"2026-10-01","2026-10-03"),true).getFirst().counterparty()).isEqualTo("Central warehouse");
    }

    @Test void supplierAndRecipientDimensionsSurviveReversal() {
        UUID supplier=UUID.randomUUID(),receipt=UUID.randomUUID(),receiver=UUID.randomUUID(),request=UUID.randomUUID();
        jdbc.update("insert into suppliers values(?,?,?,?)",supplier,"reports-test",company,"Copper supplier");
        jdbc.update("insert into goods_receipts values(?,?,?,?)",receipt,"reports-test",company,supplier);
        jdbc.update("insert into users values(?,?,?)",receiver,"reports-test","Workshop recipient");
        jdbc.update("insert into issue_requests values(?,?,?,?)",request,"reports-test",company,receiver);
        UUID movement=post(material,warehouse,"2026-10-03","RECEIPT","10","100","GOODS_RECEIPT","CANCELLED",true);
        jdbc.update("update stock_movements set reference_id=? where id=?",receipt,movement);
        UUID reversal=post(material,warehouse,"2026-10-04","ISSUE","-10","-100","REVERSAL","POSTED",true);
        jdbc.update("update stock_movements set reversal_of_id=? where id=?",movement,reversal);
        var receipts=query.rows(filter(Type.RECEIPTS,"2026-10-04","2026-10-04"),true).getFirst();
        amount(receipts.receiptQuantity(),"-10"); assertThat(receipts.counterparty()).isEqualTo("Copper supplier");
        UUID issue=post(material,warehouse,"2026-10-05","ISSUE","-2","-20","ISSUE_REQUEST","POSTED",true);
        jdbc.update("update stock_movements set reference_id=? where id=?",request,issue);
        assertThat(query.rows(filter(Type.ISSUES,"2026-10-05","2026-10-05"),true).getFirst().counterparty()).isEqualTo("Workshop recipient");
    }

    @Test void totalsAreUnpagedPerUnitAndCatalogFiltersAreLiteralAndCompanyIsolated() {
        post(material,warehouse,"2026-10-02","RECEIPT","5.1234567","50.1234",null,"POSTED",false);
        post(pcs,warehouse,"2026-10-02","RECEIPT","2","100",null,"POSTED",true);
        UUID foreign=post(material,warehouse,"2026-10-02","RECEIPT","999","999",null,"POSTED",true);
        jdbc.update("update stock_movements set company_id=? where id=?",UUID.randomUUID(),foreign);
        var f=new Filter(Type.STOCK_BALANCES,LocalDate.of(2026,10,2),LocalDate.of(2026,10,2),null,null,null,null,0,1);
        assertThat(query.count(f)).isEqualTo(2); assertThat(query.rows(f,false)).hasSize(1); assertThat(query.rows(f,true)).hasSize(2);
        assertThat(query.totals(f)).extracting(Total::uom).containsExactly("kg","pcs");
        amount(query.totals(f).getFirst().closingQuantity(),"5.1234567");
        var searched=new Filter(f.type(),f.from(),f.to(),warehouse,null,group,"100%",0,50);
        assertThat(query.rows(searched,true)).hasSize(1); assertThat(query.count(searched)).isEqualTo(1);
        TenantContext.setTenant("other-tenant"); assertThat(query.rows(f,true)).isEmpty();
    }

    @Test void subtypeFiltersEveryReportCountTotalsExportsAndReconciliation() throws Exception {
        jdbc.update("update materials set material_subtype='EQUIPMENT' where id=?",pcs);
        UUID equipment=UUID.randomUUID(),animal=UUID.randomUUID(),asset=UUID.randomUUID();
        for(UUID id:List.of(equipment,animal,asset)) {
            jdbc.update("insert into materials select ?,tenant_id,company_id,?, ?,material_group_id,unit_of_measure_id,?,? from materials where id=?",
                id,id.toString(),id.equals(animal)?"Animal":id.equals(asset)?"Fixed asset":"Second pump",id.equals(asset)?"FIXED_ASSET":"TMB",id.equals(animal)?"ANIMALS":id.equals(asset)?null:"EQUIPMENT",pcs);
        }
        // An identically named/id foreign master must never change this company's classification.
        jdbc.update("insert into materials select id,'other-tenant',?,code,name,material_group_id,unit_of_measure_id,'TMB','EQUIPMENT' from materials where id=?",UUID.randomUUID(),material);
        post(pcs,warehouse,"2026-10-02","RECEIPT","2","100",null,"POSTED",true);
        post(pcs,warehouse,"2026-10-03","ISSUE","-1","-50",null,"POSTED",true);
        post(equipment,warehouse,"2026-10-02","RECEIPT","4.0000007","200",null,"POSTED",true);
        post(equipment,warehouse,"2026-10-03","ISSUE","-2.0000001","-100",null,"POSTED",true);
        for(UUID id:List.of(material,animal,asset)) {
            post(id,warehouse,"2026-10-02","RECEIPT","5","50",null,"POSTED",true);
            post(id,warehouse,"2026-10-03","ISSUE","-1","-10",null,"POSTED",true);
        }
        jdbc.update("insert into stock_balances values(?,?,?,?,?,?,false)",pcs,warehouse,"reports-test",company,new BigDecimal("1"),new BigDecimal("50"));
        jdbc.update("insert into stock_balances values(?,?,?,?,?,?,false)",equipment,warehouse,"reports-test",company,new BigDecimal("2.0000006"),new BigDecimal("100"));
        var exporter=new WarehouseReportExport();
        for(Type type:Type.values()) {
            var f=new Filter(type,LocalDate.of(2026,10,2),LocalDate.of(2026,10,3),null,null,null,null,0,1,MaterialSubtype.EQUIPMENT);
            var report=service.generate(f,false);
            assertThat(report.materialSubtype()).isEqualTo(MaterialSubtype.EQUIPMENT);
            assertThat(report.totalElements()).isEqualTo(2);
            assertThat(report.content()).hasSize(1);
            assertThat(report.warnings()).doesNotContain("LEDGER_DIFFERS_FROM_CURRENT_BALANCES");
            var full=service.generate(f,true);
            assertThat(full.content()).extracting(Row::materialId).containsExactlyInAnyOrder(pcs,equipment);
            assertThat(full.totals()).hasSize(1);
            amount(full.totals().getFirst().closingQuantity(),type==Type.RECEIPTS || type==Type.ISSUES ? "0" : "3.0000006");
            if(type!=Type.STOCK_BALANCES) amount(full.totals().getFirst().receiptQuantity(),type==Type.ISSUES ? "0" : "6.0000007");
            for(String language:List.of("ru","en","uz")) {
                String label=language.equals("ru")?"Группа материалов: Оборудование":language.equals("en")?"Material group: Equipment":"Materiallar guruhi: Uskunalar";
                assertThat(new String(exporter.csv(full,language),java.nio.charset.StandardCharsets.UTF_8)).contains(label).doesNotContain("Fixed asset","Animal","Copper 100%");
                try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(exporter.xlsx(full,language)))) {
                    boolean found=false;
                    for(var r:workbook.getSheetAt(0)) for(var cell:r) if(cell.getCellType()==org.apache.poi.ss.usermodel.CellType.STRING && cell.getStringCellValue().equals(label)) found=true;
                    assertThat(found).isTrue();
                }
            }
            var materials=new Filter(f.type(),f.from(),f.to(),null,null,null,null,0,50,MaterialSubtype.MATERIAL);
            assertThat(query.rows(materials,true)).extracting(Row::materialId).containsExactly(material);
            var animals=new Filter(f.type(),f.from(),f.to(),null,null,null,null,0,50,MaterialSubtype.ANIMALS);
            assertThat(query.rows(animals,true)).extracting(Row::materialId).containsExactly(animal);
        }
    }

    @Test void historicalSubtypeReportsUseMappedClassificationAndExcludeUnknownLegacyReferences() {
        jdbc.update("update materials set material_subtype='EQUIPMENT' where id=?",pcs);
        post(pcs,warehouse,"2026-10-01","RECEIPT","100","1000","OPENING_STOCK_EXACT","POSTED",true);
        history(pcs,warehouse,"2026-09-01","5","50","RECEIPT");
        history(material,warehouse,"2026-09-01","7","70","RECEIPT");
        history(null,null,"2026-09-01","9","90","RECEIPT");
        var f=new Filter(Type.RECEIPTS,LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),null,null,null,null,0,50,MaterialSubtype.EQUIPMENT);
        var report=service.generate(f,true);
        assertThat(report.content()).extracting(Row::materialId).containsExactly(pcs);
        amount(report.totals().getFirst().receiptQuantity(),"5");
        amount(report.totals().getFirst().receiptAmount(),"50");
    }

    @Test void fixedAssetsAndServicesFilterRowsCountsTotalsReconciliationAndBothExports() throws Exception {
        post(material,warehouse,"2026-10-02","RECEIPT","99","990",null,"POSTED",true);
        var exporter=new WarehouseReportExport();
        for (MaterialType materialType:List.of(MaterialType.FIXED_ASSET,MaterialType.SERVICES)) {
            var ids=List.of(UUID.randomUUID(),UUID.randomUUID());
            for (UUID id:ids) {
                jdbc.update("insert into materials select ?,tenant_id,company_id,?, ?,material_group_id,unit_of_measure_id,?,null from materials where id=?",
                    id,id.toString(),materialType.name(),materialType.name(),pcs);
                // Other tenants and companies cannot broaden this filter.
                jdbc.update("insert into materials select id,'other-tenant',?,code,name,material_group_id,unit_of_measure_id,'TMB',null from materials where id=?",UUID.randomUUID(),id);
                post(id,warehouse,"2026-10-02","RECEIPT","3","30",null,"POSTED",true);
                post(id,warehouse,"2026-10-03","ISSUE","-1","-10",null,"POSTED",true);
                jdbc.update("insert into stock_balances values(?,?,?,?,?,?,false)",id,warehouse,"reports-test",company,new BigDecimal("2"),new BigDecimal("20"));
            }
            for (Type type:Type.values()) {
                var f=new Filter(type,LocalDate.of(2026,10,2),LocalDate.of(2026,10,3),null,null,null,null,0,1,null,materialType);
                var paged=service.generate(f,false);
                assertThat(paged.materialType()).isEqualTo(materialType);
                assertThat(paged.materialSubtype()).isNull();
                assertThat(paged.totalElements()).isEqualTo(2);
                assertThat(paged.content()).hasSize(1);
                assertThat(paged.warnings()).doesNotContain("LEDGER_DIFFERS_FROM_CURRENT_BALANCES");
                var full=service.generate(f,true);
                assertThat(full.content()).extracting(Row::materialId).containsExactlyInAnyOrderElementsOf(ids);
                amount(full.totals().getFirst().closingAmount(),type==Type.RECEIPTS || type==Type.ISSUES ? "0" : "40");
                if (type!=Type.STOCK_BALANCES) {
                    amount(full.totals().getFirst().receiptQuantity(),type==Type.ISSUES ? "0" : "6");
                    amount(full.totals().getFirst().issueQuantity(),type==Type.RECEIPTS ? "0" : "2");
                }
                for (String language:List.of("ru","en","uz")) {
                    String label=materialType==MaterialType.FIXED_ASSET
                        ? switch(language) { case "en" -> "Material group: Fixed assets"; case "uz" -> "Materiallar guruhi: Asosiy vositalar"; default -> "Группа материалов: Основные средства"; }
                        : switch(language) { case "en" -> "Material group: Services"; case "uz" -> "Materiallar guruhi: Xizmatlar"; default -> "Группа материалов: Услуги"; };
                    assertThat(new String(exporter.csv(full,language),java.nio.charset.StandardCharsets.UTF_8)).contains(label).doesNotContain("Copper 100%","Inventory subtype","Вид ТМЦ");
                    try (var workbook=new XSSFWorkbook(new ByteArrayInputStream(exporter.xlsx(full,language)))) {
                        boolean found=false;
                        for (var r:workbook.getSheetAt(0)) for (var cell:r) if (cell.getCellType()==org.apache.poi.ss.usermodel.CellType.STRING && cell.getStringCellValue().equals(label)) found=true;
                        assertThat(found).isTrue();
                    }
                }
            }
            history(ids.getFirst(),warehouse,"2026-09-01","7","70","RECEIPT");
            history(material,warehouse,"2026-09-01","9","90","RECEIPT");
            history(null,null,"2026-09-01","9","90","RECEIPT");
            var historical=service.generate(new Filter(Type.RECEIPTS,LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),null,null,null,null,0,50,null,materialType),true);
            assertThat(historical.content()).extracting(Row::materialId).containsExactly(ids.getFirst());
            amount(historical.totals().getFirst().receiptQuantity(),"7");
        }
        assertThatThrownBy(()->service.generate(new Filter(Type.RECEIPTS,LocalDate.of(2026,10,2),LocalDate.of(2026,10,3),null,null,null,null,0,50,MaterialSubtype.EQUIPMENT,MaterialType.SERVICES),true))
            .isInstanceOf(BusinessException.class);
    }

    @Test void importedUnitsHaveReadableLabelsInRowsTotalsAndEveryExportWithoutMergingSourceUnits() throws Exception {
        String first="1C-UOM-source-tonne",second="1C-UOM-other-tonne";
        jdbc.update("update unit_of_measures set code=?,measurement_name_short=?,measurement_name_short_ru=?,measurement_name_short_uz=? where id=(select unit_of_measure_id from materials where id=?)",
            first,"ТН [1C 86]","ТН","t",material);
        jdbc.update("update unit_of_measures set code=?,measurement_name_short=' ',measurement_name_long=?,measurement_name_long_ru=? where id=(select unit_of_measure_id from materials where id=?)",
            second,"tonne","ТН",pcs);
        post(material,warehouse,"2026-10-02","RECEIPT","120.0000007","100",null,"POSTED",true);
        post(pcs,warehouse,"2026-10-02","RECEIPT","0.008","10",null,"POSTED",true);
        post(material,warehouse,"2026-10-03","ISSUE","-1","-1",null,"POSTED",true);
        var exporter=new WarehouseReportExport();
        for(Type type:Type.values()) {
            var report=service.generate(filter(type,"2026-10-02","2026-10-03"),true);
            Row row=report.content().stream().filter(r->r.materialId().equals(material)).findFirst().orElseThrow();
            assertThat(row.uom()).isEqualTo(first);
            assertThat(row.materialGroupId()).isEqualTo(group);
            assertThat(row.groupCode()).isEqualTo("10");
            assertThat(row.materialGroupName()).contains("ru: Металлы");
            assertThat(row.warehouseId()).isEqualTo(warehouse);
            assertThat(row.warehouseCode()).isEqualTo("01");
            assertThat(row.unitOfMeasureShortNameRu()).isEqualTo("ТН");
            assertThat(report.totals()).filteredOn(t->t.uom().equals(first)).extracting(Total::unitOfMeasureShortNameRu).containsExactly("ТН");
            for(String language:List.of("ru","en","uz")) {
                String expected=language.equals("uz")?"t":"ТН";
                String csv=new String(exporter.csv(report,language),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(csv).contains("\""+expected+"\"").doesNotContain("1C-UOM-").doesNotContain("[1C 86]");
                try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(exporter.xlsx(report,language)))) {
                    int units=0;
                    for(var r:workbook.getSheetAt(0)) for(var cell:r) {
                        if(cell.getCellType()!=org.apache.poi.ss.usermodel.CellType.STRING) continue;
                        String text=cell.getStringCellValue();
                        assertThat(text).doesNotContain("1C-UOM-").doesNotContain("[1C 86]");
                        if(text.equals(expected)) units++;
                    }
                    assertThat(units).isGreaterThanOrEqualTo(2);
                }
            }
        }
        var totals=query.totals(filter(Type.STOCK_BALANCES,"2026-10-02","2026-10-03"));
        assertThat(totals).hasSize(2); // Identical printed symbols must not combine different source units.
        assertThat(totals).extracting(Total::unitOfMeasureShortNameRu).containsOnly("ТН");
        assertThat(totals).filteredOn(t->t.uom().equals(second)).extracting(Total::unitOfMeasureShortName).containsExactly("tonne");
        assertThat(WarehouseReportExport.unitLabel("kg",null,null,null,"ru")).isEqualTo("kg");
        assertThat(WarehouseReportExport.unitLabel(null,null,null,null,"ru")).isEqualTo("—");
    }

    @Test void landedCostsAffectValueWithoutInventingPhysicalReceipts() {
        post(material,warehouse,"2026-10-01","RECEIPT","100","1000","OPENING_STOCK_EXACT","POSTED",true);
        jdbc.update("insert into stock_cost_movements values(?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),"reports-test",company,material,warehouse,LocalDateTime.parse("2026-10-04T12:00:00"),"LANDED_COST_ALLOCATION",UUID.randomUUID(),"LANDED_COST_ADJUSTMENT",new BigDecimal("50"),false,"POSTED",null);
        var before=query.rows(filter(Type.STOCK_BALANCES,"2026-10-03","2026-10-03"),true).getFirst(); amount(before.closingAmount(),"1000");
        var after=query.rows(filter(Type.MATERIAL_STATEMENT,"2026-10-04","2026-10-04"),true).getFirst();
        amount(after.receiptQuantity(),"0"); amount(after.receiptAmount(),"50"); amount(after.closingAmount(),"1050");
    }

    @Test void olderOpeningImportsAndZeroValueStockRemainReportable() {
        jdbc.execute("drop table exact_opening_imports");
        post(material,warehouse,"2026-10-01","RECEIPT","12.1234567","0","OPENING_STOCK","POSTED",true);
        assertThat(query.coverageStart()).isEqualTo(LocalDate.of(2026,10,1));
        assertThat(query.importInProgress()).isFalse();
        var balance=service.generate(filter(Type.STOCK_BALANCES,"2026-10-02","2026-10-02"),false).content().getFirst();
        amount(balance.closingQuantity(),"12.1234567"); amount(balance.closingAmount(),"0"); amount(balance.price(),"0");
        assertThat(query.rows(filter(Type.RECEIPTS,"2026-10-01","2026-10-02"),true)).isEmpty();
    }

    @Test void serviceRejectsUnavailableHistoryIncompleteImportsAndWarehouseScopeAndFlagsReconciliation() {
        assertThatThrownBy(()->service.generate(filter(Type.MATERIAL_STATEMENT,"2026-09-01","2026-10-02"),false)).isInstanceOf(BusinessException.class).hasMessageContaining("2026-10-01");
        jdbc.update("update exact_opening_imports set status='IMPORTING'");
        assertThatThrownBy(()->service.generate(filter(Type.STOCK_BALANCES,"2026-10-01","2026-10-02"),false)).hasMessageContaining("reconciled");
        jdbc.update("update exact_opening_imports set status='COMPLETED'");
        post(material,warehouse,"2026-10-01","RECEIPT","5","50","OPENING_STOCK_EXACT","POSTED",true);
        var report=service.generate(filter(Type.STOCK_BALANCES,"2026-10-02","2026-10-02"),false);
        assertThat(report.warnings()).contains("LEDGER_DIFFERS_FROM_CURRENT_BALANCES");
        jdbc.update("insert into stock_balances values(?,?,?,?,?,?,?)",material,warehouse,"reports-test",company,new BigDecimal("5"),new BigDecimal("50"),false);
        assertThat(service.generate(filter(Type.STOCK_BALANCES,"2026-10-02","2026-10-02"),false).warnings()).doesNotContain("LEDGER_DIFFERS_FROM_CURRENT_BALANCES");
        when(access.warehouseReadScope(null,null)).thenReturn(java.util.Set.of(target));
        assertThat(service.generate(filter(Type.STOCK_BALANCES,"2026-10-02","2026-10-02"),false).content()).isEmpty();
        when(access.warehouseReadScope(warehouse,null)).thenThrow(new org.springframework.security.access.AccessDeniedException("Denied"));
        assertThatThrownBy(()->service.generate(new Filter(Type.STOCK_BALANCES,LocalDate.of(2026,10,2),LocalDate.of(2026,10,2),warehouse,null,null,null,0,50),true)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void workbookAndCsvExportAllRowsExactDecimalsAndSafeText() throws Exception {
        post(material,warehouse,"2026-10-01","RECEIPT","1000000000.1234567","1000.0123","OPENING_STOCK_EXACT","POSTED",true);
        post(pcs,warehouse,"2026-10-01","RECEIPT","2","10","OPENING_STOCK_EXACT","POSTED",true);
        jdbc.update("update materials set name=? where id=?","=HYPERLINK(\"https://example.org\")",pcs);
        var report=service.generate(new Filter(Type.MATERIAL_STATEMENT,LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),warehouse,null,null,null,0,1),true);
        assertThat(report.content()).hasSize(2);
        var exporter=new WarehouseReportExport();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(exporter.xlsx(report,"ru")))) {
            var sheet=workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Отчёт");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).startsWith("Материальная ведомость · 01.10.2026");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).startsWith("Период: 01.10.2026 — 02.10.2026");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).contains("Склад: Central warehouse");
            assertThat(sheet.getNumMergedRegions()).isGreaterThan(4);
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            boolean precise=false;
            for(var r:sheet) for(var cell:r) {
                assertThat(cell.getCellType()).isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
                if(cell.getCellType()==org.apache.poi.ss.usermodel.CellType.STRING && cell.getStringCellValue().equals("1000000000.1234567")) precise=true;
            }
            assertThat(precise).isTrue();
        }
        String csv=new String(exporter.csv(report,"ru"),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\ufeff").contains("1000000000.1234567").contains("'=").contains("000002");
        assertThat(csv).contains("Период: 01.10.2026 — 02.10.2026").contains("Склад: Central warehouse").doesNotContain(warehouse.toString());
        var receipts=service.generate(new Filter(Type.RECEIPTS,LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),warehouse,null,null,null,0,50),true);
        assertThat(new String(exporter.csv(receipts,"ru"),java.nio.charset.StandardCharsets.UTF_8)).contains("Счёт Кт").contains("Номенкл. №");
        when(access.warehouseReadScope(null,null)).thenReturn(java.util.Set.of(warehouse));
        assertThat(service.options().warehouseName()).isEqualTo("Central warehouse");
    }

    @Test void overviewKeepsUnitsSeparateAndApprovalStoresImmutableRevisions() {
        post(material,warehouse,"2026-10-01","RECEIPT","10","100","OPENING_STOCK_EXACT","POSTED",true);
        post(pcs,warehouse,"2026-10-01","RECEIPT","2","40","OPENING_STOCK_EXACT","POSTED",true);
        post(material,target,"2026-10-01","RECEIPT","5","50","OPENING_STOCK_EXACT","POSTED",true);
        jdbc.update("insert into stock_balances values(?,?,?,?,?,?,?)",material,warehouse,"reports-test",company,new BigDecimal("10"),new BigDecimal("100"),false);
        jdbc.update("insert into stock_balances values(?,?,?,?,?,?,?)",pcs,warehouse,"reports-test",company,new BigDecimal("2"),new BigDecimal("40"),false);
        jdbc.update("insert into stock_balances values(?,?,?,?,?,?,?)",material,target,"reports-test",company,new BigDecimal("5"),new BigDecimal("50"),false);

        var named=new NamedParameterJdbcTemplate(jdbc);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var store=new WarehouseReportApprovalStore(named,mapper,query);
        var overview=new WarehouseOverviewService(service,query,mock(WarehouseAccountReportQuery.class),store);
        var approvals=new WarehouseReportApprovalService(overview,store,query,named,access,mapper);
        var from=LocalDate.of(2026,10,2);var to=LocalDate.of(2026,10,3);
        var lines=overview.overview(from,to,null).warehouses();
        assertThat(lines).hasSize(2);
        var central=lines.stream().filter(line->line.warehouseId().equals(warehouse)).findFirst().orElseThrow();
        amount(central.closingAmount(),"140");
        assertThat(central.quantities()).extracting(WarehouseOverview.UnitTotal::uom).containsExactly("kg","pcs");
        amount(central.quantities().get(0).closingQuantity(),"10");
        amount(central.quantities().get(1).closingQuantity(),"2");

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("manager","n/a"));
        var first=approvals.approve(warehouse,from,to);
        assertThat(first.approval().revision()).isEqualTo(1);
        assertThat(approvals.approve(warehouse,from,to).approval().id()).isEqualTo(first.approval().id());

        post(material,warehouse,"2026-10-03","RECEIPT","1","10","PURCHASE_ORDER","POSTED",true);
        jdbc.update("update stock_balances set quantity=11,total_value=110 where material_id=? and warehouse_id=?",material,warehouse);
        var second=approvals.approve(warehouse,from,to);
        assertThat(second.approval().revision()).isEqualTo(2);
        assertThat(second.approval().id()).isNotEqualTo(first.approval().id());
        amount(store.byId(first.approval().id()).report().content().stream().filter(row->row.materialId().equals(material)).findFirst().orElseThrow().closingQuantity(),"10");
        amount(second.report().content().stream().filter(row->row.materialId().equals(material)).findFirst().orElseThrow().closingQuantity(),"11");
        assertThat(approvals.approveAll(from,to).warehouseCount()).isEqualTo(2);
        assertThat(store.latest(warehouse,from,to).revision()).isEqualTo(2);
        assertThat(store.latest(target,from,to).revision()).isEqualTo(1);
    }
}

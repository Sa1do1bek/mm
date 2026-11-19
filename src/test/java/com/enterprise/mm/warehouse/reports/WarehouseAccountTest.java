package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.service.*;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.enterprise.mm.warehouse.reports.WarehouseReport.*;
import static com.enterprise.mm.warehouse.reports.WarehouseAccountImportDtos.*;

@org.testcontainers.junit.jupiter.Testcontainers
class WarehouseAccountTest {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres=new org.testcontainers.containers.PostgreSQLContainer<>("postgres:17-alpine");
    final String tenant="account-test",organization="a".repeat(32),materialSource="b".repeat(32),warehouseSource="c".repeat(32),accountSource="d".repeat(32);
    final UUID company=UUID.randomUUID(),warehouse=UUID.randomUUID(),material=UUID.randomUUID(),runId=UUID.randomUUID();
    final LocalDate snapshot=LocalDate.parse("2026-10-08");
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();JdbcTemplate jdbc;TransactionTemplate tx;
    WarehouseAccountImportService importer;WarehouseAccountReportQuery query;WarehouseReportService reports;
    @BeforeEach void setup() throws Exception {
        TenantContext.setTenant(tenant);CompanyContext.setCompany(company);
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        for(String sql:List.of(
            "create table companies(id uuid,tenant_id varchar,name varchar,legal_name varchar)",
            "create table materials(id uuid,tenant_id varchar,company_id uuid,code varchar,name varchar,description varchar,is_delete boolean default false,material_group_id uuid,unit_of_measure_id uuid,ikpu_code varchar,material_type varchar default 'TMB',material_subtype varchar)",
            "create table warehouses(id uuid,tenant_id varchar,company_id uuid,code varchar,name varchar,description varchar,address varchar,is_delete boolean default false)",
            "create table material_groups(id uuid,tenant_id varchar,company_id uuid,code varchar,name varchar,description varchar,is_delete boolean default false)",
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
            "create table chart_of_accounts(id uuid primary key,tenant_id varchar(80) not null,company_id uuid not null,created_at timestamp not null,updated_at timestamp,version bigint not null,is_delete boolean not null,deleted_at timestamp,code varchar(80),account_code varchar(40) not null,name text not null,status varchar(50),is_group boolean not null,source_system varchar(40),external_id varchar(120),last_synced_at timestamp,unique(tenant_id,company_id,account_code))",
            "create table issue_requests(id uuid,tenant_id varchar,company_id uuid,receiver_user_id uuid)",
            "create table users(id uuid,tenant_id varchar,full_name varchar)",
            "create table warehouse_imported_movements(id uuid,tenant_id varchar,company_id uuid,document_key varchar,movement_date date,source_record_id uuid,target_warehouse_id uuid,movement_type varchar,quantity numeric,amount numeric,material_id uuid,warehouse_id uuid,material_source_id varchar,warehouse_source_id varchar,material_name varchar,warehouse_name varchar,material_code varchar,warehouse_code varchar)"
        )) jdbc.execute(sql);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__warehouse_source_history.sql")).execute(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V12__warehouse_account_balances.sql")).execute(ds);
        jdbc.execute(new ClassPathResource("db/migration/V13__warehouse_source_completion.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.execute(new ClassPathResource("db/migration/V32__warehouse_account_snapshot_identity.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("insert into companies values(?,?,?,?)",company,tenant,"Навоийазот","АО Навоийазот");
        jdbc.update("insert into warehouses(id,tenant_id,company_id,code,name,description) values(?,?,?,?,?,?)",warehouse,tenant,company,"129","129.0 склад","[1C:navoiyazot83:Catalog85:"+warehouseSource+"]");
        jdbc.update("insert into materials(id,tenant_id,company_id,code,name,description,material_type,material_subtype) values(?,?,?,?,?,?,?,?)",material,tenant,company,"45997530","Декоративный бетонный забор","[1C:navoiyazot83:Catalog54:"+materialSource+"]","FIXED_ASSET",null);
        var companies=mock(CompanyRepository.class);var org=new Company();org.setDescription("[1C:navoiyazot83:Catalog61:"+organization+"]");
        when(companies.findByTenantIdAndIdAndIsDeletedFalse(tenant,company)).thenReturn(Optional.of(org));
        importer=new WarehouseAccountImportService(jdbc,mapper,Validation.buildDefaultValidatorFactory().getValidator(),companies,mock(InventoryScopeLockService.class));
        var named=new NamedParameterJdbcTemplate(jdbc);var operational=new WarehouseReportQuery(named);query=new WarehouseAccountReportQuery(named,operational);
        var access=mock(RoleDataScopeService.class);when(access.warehouseReadScope(any(),any())).thenAnswer(call -> call.getArgument(0)==null ? java.util.Set.of() : java.util.Set.of((java.util.UUID)call.getArgument(0)));
        var settings=mock(InventoryAccountingSettingsService.class);when(settings.findOrDefault()).thenReturn(new InventoryAccountingSettings());
        reports=new WarehouseReportService(operational,access,settings,named,query);
    }
    @AfterEach void cleanup(){jdbc.execute("drop schema public cascade");jdbc.execute("create schema public");TenantContext.clear();CompanyContext.clear();}
    WarehouseAccountImportDtos.Row source(long seq,String kind,String account,String type,String quantity,String amount,String date) throws Exception {
        String ref=type.equals("FINISHED_GOODS")?"00000037":"00000036";
        var data=mapper.createObjectNode().put("organizationSourceId",organization).put("kind",kind).put("sourceDate",date)
            .put("accountSourceId",accountSource).put("accountCode",account).put("accountName","Счёт "+account).put("materialReferenceType",ref)
            .put("materialSourceId",materialSource).put("warehouseSourceId",warehouseSource).put("materialCode","45997530")
            .put("materialName","Декоративный бетонный забор").put("warehouseCode","129").put("warehouseName","129.0 склад")
            .put("sourceType",type).put("sourceMaterialType",type.equals("FIXED_ASSET")?"Основное средство приобретённое":type.equals("FINISHED_GOODS")?"Готовая продукция":"Материалы")
            .put("stockCategory",WarehouseAccountImportService.category(account)).put("uom","1C-UOM-PCS").put("uomNameRu","шт")
            .put("quantity",quantity).put("amount",amount).put("correspondingAccount","6010").put("counterparty","—").put("warehouseId",warehouse.toString());
        if(!type.equals("FINISHED_GOODS"))data.put("materialId",material.toString());
        data.putObject("additionalDimensions").put("sourceDimension",String.valueOf(seq));
        if(kind.equals("BALANCE"))data.put("sourceKey","balance:_accrgat24298:"+accountSource+":"+WarehouseAccountImportService.sha(mapper.writeValueAsString(data.get("additionalDimensions"))));
        else {
            String doc="document:_document162:"+UUID.nameUUIDFromBytes(("doc"+seq).getBytes()).toString().replace("-","");data.put("documentKey",doc);
            data.put("sourceKey","movement:"+WarehouseAccountImportService.sha(String.join(":",doc,date,accountSource,ref,materialSource,warehouseSource)));
        }
        String content=mapper.writeValueAsString(data);return new WarehouseAccountImportDtos.Row(seq,WarehouseAccountImportService.sha(content),content);
    }
    String chain(List<WarehouseAccountImportDtos.Row> rows)throws Exception {String value="0".repeat(64);for(var r:rows)value=WarehouseAccountImportService.sha(value+"\n"+mapper.readTree(r.content()).path("sourceKey").asText()+":"+r.sha256()+"\n");return value;}
    void start(List<WarehouseAccountImportDtos.Row> rows)throws Exception {
        var counts=new TreeMap<String,Long>();counts.put("BALANCE",0L);counts.put("MOVEMENT",0L);
        for(var r:rows)counts.merge(mapper.readTree(r.content()).path("kind").asText(),1L,Long::sum);
        tx.execute(s->importer.start(tenant,company,new Start(runId,"e".repeat(64),organization,snapshot,counts,uncheckedChain(rows))));
    }
    String uncheckedChain(List<WarehouseAccountImportDtos.Row> rows){try{return chain(rows);}catch(Exception e){throw new IllegalStateException(e);}}
    Map<String,Object> accept(List<WarehouseAccountImportDtos.Row> rows){return tx.execute(s->importer.chunk(tenant,company,runId,new Chunk(rows)));}
    void complete(){tx.execute(s->importer.complete(tenant,company,runId));}
    Filter filter(Type type,String from,String to,String account,String sourceType){return new Filter(type,LocalDate.parse(from),LocalDate.parse(to),null,null,null,null,0,50,null,null,account,sourceType);}
    void exact(String actual,String expected){assertThat(new BigDecimal(actual)).isEqualByComparingTo(expected);}
    @Test void importedAccountsBecomeManagedChartAccountsWithoutChangingSourceEvidence() throws Exception {
        UUID managedId = UUID.randomUUID();
        jdbc.update("insert into chart_of_accounts(id,tenant_id,company_id,created_at,version,is_delete,code,account_code,name,status,is_group) values(?,?,?,?,0,false,?,?,?,'ACTIVE',false)",
            managedId,tenant,company,java.sql.Timestamp.valueOf(LocalDateTime.now()),"1010","1010","Finance name");
        var rows = List.of(source(1,"BALANCE","1010","MATERIAL","2","20","2026-10-08"),
            source(2,"BALANCE","0820","FIXED_ASSET","1","3","2026-10-08"));
        start(rows);accept(rows);complete();
        assertThat(jdbc.queryForObject("select count(*) from chart_of_accounts where tenant_id=? and company_id=?",Long.class,tenant,company)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select name from chart_of_accounts where account_code='1010'",String.class)).isEqualTo("Finance name");
        UUID importedId = jdbc.queryForObject("select id from chart_of_accounts where account_code='0820'",UUID.class);
        assertThat(importedId).isNotNull();
        jdbc.update("update chart_of_accounts set name='Managed asset account' where id=?",importedId);
        var accountFilter = new Filter(Type.STOCK_BALANCES,snapshot,snapshot,warehouse,null,null,"Managed asset",0,50);
        assertThat(query.references("ACCOUNT",accountFilter).content()).singleElement().satisfies(account -> {
            assertThat(account.get("id")).isEqualTo(importedId.toString());
            assertThat(account.get("name")).isEqualTo("Managed asset account");
        });
        assertThat(query.accounts()).anySatisfy(account -> assertThat(account).containsEntry("name","Managed asset account"));
        assertThat(reports.generate(filter(Type.STOCK_BALANCES,"2026-10-08","2026-10-08","0820",null),true).content())
            .singleElement().satisfies(row -> assertThat(row.accountName()).isEqualTo("Managed asset account"));
        assertThat(jdbc.queryForObject("select account_name from warehouse_account_entries where account_code='0820'",String.class))
            .isEqualTo("Счёт 0820");
    }

    private List<String> receiptAccountCodes(UUID materialId) {
        var value = new Material(); value.setId(materialId); value.setMaterialType(MaterialType.TMB); value.setStatus("ACTIVE");
        var resolution = new MaterialStockAccountResolutionService(new NamedParameterJdbcTemplate(jdbc),
            mock(com.enterprise.mm.repository.MaterialRepository.class), mock(StockAccountResolver.class),
            mock(com.enterprise.mm.repository.ChartOfAccountRepository.class), mock(RoleDataScopeService.class));
        return resolution.resolve(value).candidateAccounts().stream().map(MaterialStockAccountResolutionService.Account::code).toList();
    }

    @Test void manualReceiptEvidenceUsesOnlyCurrentCompletedEligibleMaterialAccounts() throws Exception {
        var first = source(1,"BALANCE","1016","MATERIAL","13","30","2026-10-08");
        var second = source(2,"BALANCE","1020","MATERIAL","2","10","2026-10-08");
        var custody = source(3,"BALANCE","002","MATERIAL","2","10","2026-10-08");
        var rows = List.of(first, second, custody);
        start(rows); accept(rows);
        assertThat(receiptAccountCodes(material)).isEmpty();
        complete();
        assertThat(receiptAccountCodes(material))
            .containsExactly("1016", "1020");
        assertThat(receiptAccountCodes(UUID.randomUUID())).isEmpty();
        jdbc.update("update chart_of_accounts set status='INACTIVE' where account_code='1020'");
        assertThat(receiptAccountCodes(material)).containsExactly("1016");
        CompanyContext.setCompany(UUID.randomUUID());
        assertThat(receiptAccountCodes(material)).isEmpty();
    }

    @Test void manualReceiptEvidenceDoesNotIncludeOlderSnapshots() throws Exception {
        var older = source(1,"BALANCE","1016","MATERIAL","1","10","2026-10-08");
        start(List.of(older)); accept(List.of(older)); complete();
        var newer = source(1,"BALANCE","1020","MATERIAL","1","10","2026-10-09");
        UUID successor = UUID.randomUUID();
        tx.execute(s -> importer.start(tenant,company,new Start(successor,"f".repeat(64),organization,snapshot.plusDays(1),
            Map.of("BALANCE",1L,"MOVEMENT",0L),uncheckedChain(List.of(newer)))));
        tx.execute(s -> importer.chunk(tenant,company,successor,new Chunk(List.of(newer))));
        tx.execute(s -> importer.complete(tenant,company,successor));
        assertThat(receiptAccountCodes(material)).containsExactly("1020");
    }

    @Test void manualReceiptEvidenceFollowsScopedSourceMaterialLinks() throws Exception {
        var row = source(1,"BALANCE","1016","MATERIAL","1","10","2026-10-08");
        start(List.of(row)); accept(List.of(row)); complete();
        jdbc.update("update warehouse_account_entries set material_id=null where import_id=?",runId);
        UUID historyRun=UUID.randomUUID(), sourceRecord=UUID.randomUUID();
        jdbc.update("insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,expected_chain,chain_sha256,status) values(?,?,?,?,?,'{}',1,?,?,'COMPLETED')",historyRun,tenant,company,"a".repeat(64),organization,"b".repeat(64),"c".repeat(64));
        jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,row_sha256,content) values(?,?,?,?,1,'MATERIAL','local-link','catalog',?,'{}')",sourceRecord,tenant,company,historyRun,"d".repeat(64));
        jdbc.update("insert into warehouse_source_master_links(tenant_id,company_id,reference_kind,reference_type,source_id,master_id,source_record_id) values(?,?,'MATERIAL','00000036',?,?,?)",tenant,company,materialSource,material,sourceRecord);
        assertThat(receiptAccountCodes(material)).containsExactly("1016");
        assertThat(receiptAccountCodes(UUID.randomUUID())).isEmpty();
    }

    @Test void migrationRegistersAccountsFromPreviouslyCompletedImports() throws Exception {
        var row=source(1,"BALANCE","1010","MATERIAL","2","20","2026-10-08");
        start(List.of(row));accept(List.of(row));complete();
        jdbc.update("delete from chart_of_accounts where account_code='1010'");
        jdbc.execute(new ClassPathResource("db/migration/V70__register_imported_warehouse_accounts.sql")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("select name from chart_of_accounts where account_code='1010'",String.class))
            .isEqualTo("Счёт 1010");
    }
    @Test void allAccountsSignedAndValueOnlyPositionsAreImportedExactlyAndReplayedWithoutDuplicates()throws Exception {
        var rows=List.of(source(1,"BALANCE","0820","FIXED_ASSET","1550","0","2026-10-08"),
            source(2,"BALANCE","002","MATERIAL","-0.1234567","0","2026-10-08"),source(3,"BALANCE","2810","FINISHED_GOODS","0","9999999999999.9999","2026-10-08"));
        start(rows);assertThat(query.available()).isFalse();var first=accept(rows);complete();assertThat(accept(rows)).isEqualTo(first);
        assertThat(importer.status(tenant,company,runId)).containsEntry("acceptedRecords",3L).containsEntry("status","COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from warehouse_account_entries",Long.class)).isEqualTo(3);
        var report=reports.generate(filter(Type.STOCK_BALANCES,"2026-10-08","2026-10-08",null,null),true);
        assertThat(report.content()).hasSize(3);assertThat(report.warnings()).contains("ACCOUNT_WAREHOUSE_BALANCES");assertThat(report.totals()).hasSize(3);
        var warehouseTotals=query.warehouseTotals(filter(Type.MATERIAL_STATEMENT,"2026-10-08","2026-10-08",null,null));
        assertThat(warehouseTotals).hasSize(1);
        exact(warehouseTotals.getFirst().closingQuantity(),"1549.8765433");
        exact(warehouseTotals.getFirst().closingAmount(),"9999999999999.9999");
        exact(report.content().stream().filter(r->r.accountCode().equals("002")).findFirst().orElseThrow().closingQuantity(),"-0.1234567");
        exact(report.content().stream().filter(r->r.accountCode().equals("2810")).findFirst().orElseThrow().closingAmount(),"9999999999999.9999");
        assertThat(reports.generate(filter(Type.STOCK_BALANCES,"2026-10-08","2026-10-08",null,"FINISHED_GOODS"),false).totalElements()).isEqualTo(1);
        var assets=reports.generate(new Filter(Type.STOCK_BALANCES,snapshot,snapshot,null,null,null,null,0,50,null,MaterialType.FIXED_ASSET,"0820",null),true);
        assertThat(assets.content()).hasSize(1);exact(assets.content().getFirst().closingQuantity(),"1550");
        assertThat(query.references("MATERIAL",filter(Type.STOCK_BALANCES,"2026-10-08","2026-10-08",null,"FINISHED_GOODS")).content()).hasSize(1);
        var accountFilter = new Filter(Type.STOCK_BALANCES, snapshot, snapshot, warehouse,
            null, null, null, 0, 50);
        assertThat(query.references("ACCOUNT", accountFilter).content()).hasSize(3);
        var otherWarehouse = new Filter(Type.STOCK_BALANCES, snapshot, snapshot, UUID.randomUUID(),
            null, null, null, 0, 50);
        assertThat(query.references("ACCOUNT", otherWarehouse).content()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from stock_balances",Long.class)).isZero();
        var exporter=new WarehouseReportExport();String csv=new String(exporter.csv(assets,"ru"),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("Счёт учёта: 0820","Вид номенклатуры (1С)","Основное средство приобретённое","1550","шт");
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(exporter.xlsx(assets,"ru")))) {
            assertThat(book.getSheetAt(0).rowIterator()).toIterable().anySatisfy(row->assertThat(row.getCell(1)==null?"":row.getCell(1).toString()).isEqualTo("0820"));
        }
    }
    @Test void completedSnapshotAcceptsMissingCatalogAddendumWithoutRewritingOriginalProof() throws Exception {
        var first=source(1,"BALANCE","0820","FIXED_ASSET","1550","0","2026-10-08");
        start(List.of(first));accept(List.of(first));complete();String oldProof=(String)importer.proof(tenant,company,runId,0,"0".repeat(64)).get("chainSha256");
        var d=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(source(1,"BALANCE","2990","MATERIAL","1.0000007","0","2026-10-08").content());
        d.put("materialReferenceType","000016f4").put("sourceType","GOODS").put("sourceMaterialType","Товары");d.remove("materialId");((com.fasterxml.jackson.databind.node.ObjectNode)d.get("additionalDimensions")).put("sourceDimension","goods-addendum");
        d.put("sourceKey","balance:_accrgat24298:"+accountSource+":"+WarehouseAccountImportService.sha(mapper.writeValueAsString(d.get("additionalDimensions"))));
        String content=mapper.writeValueAsString(d);var extra=new WarehouseAccountImportDtos.Row(1,WarehouseAccountImportService.sha(content),content);UUID next=UUID.randomUUID();
        var command=new Start(next,"f".repeat(64),organization,snapshot,Map.of("BALANCE",1L,"MOVEMENT",0L),chain(List.of(extra)));
        tx.execute(status->importer.start(tenant,company,command));
        tx.execute(status->importer.chunk(tenant,company,next,new Chunk(List.of(extra))));tx.execute(status->importer.complete(tenant,company,next));
        assertThat(query.snapshotDate()).isEqualTo(snapshot);assertThat(query.accounts()).hasSize(2);
        assertThat(reports.generate(filter(Type.STOCK_BALANCES,"2026-10-08","2026-10-08","2990","GOODS"),true).content()).hasSize(1);
        assertThat(importer.proof(tenant,company,runId,0,"0".repeat(64))).containsEntry("chainSha256",oldProof);
        assertThat(jdbc.queryForObject("select count(*) from stock_balances",Long.class)).isZero();
        UUID duplicate=UUID.randomUUID();tx.execute(status->importer.start(tenant,company,new Start(duplicate,"1".repeat(64),organization,snapshot,Map.of("BALANCE",1L,"MOVEMENT",0L),chainUnchecked(extra))));
        assertThatThrownBy(()->tx.execute(status->importer.chunk(tenant,company,duplicate,new Chunk(List.of(extra))))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_account_entries",Long.class)).isEqualTo(2);
    }
    String chainUnchecked(WarehouseAccountImportDtos.Row row){return uncheckedChain(List.of(row));}
    @Test void differentSnapshotAndInventedValueOnlyQuantityAreRejected() throws Exception {
        var first=source(1,"BALANCE","1010","MATERIAL","2","0","2026-10-08");start(List.of(first));accept(List.of(first));complete();
        assertThatThrownBy(()->importer.start(tenant,company,new Start(UUID.randomUUID(),"f".repeat(64),organization,snapshot.minusDays(1),Map.of("BALANCE",1L,"MOVEMENT",0L),"0".repeat(64)))).isInstanceOf(ConflictException.class);
        var d=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(first.content());
        d.put("sourceType","VALUE_ONLY").put("materialReferenceType","NONE").put("materialSourceId","0".repeat(32));d.remove("materialId");
        assertThatThrownBy(()->importer.validate(d,organization,snapshot)).isInstanceOf(BadRequestException.class);
        d.put("quantity","0").put("amount","10").put("uom","NO_QUANTITY").put("accountCode","1070").put("stockCategory","PRECIOUS_METALS_CUSTODY");assertThatCode(()->importer.validate(d,organization,snapshot)).doesNotThrowAnyException();
    }
    @Test void completedSnapshotCanBeFollowedByANewerSnapshotThroughTheImportApi() throws Exception {
        var first=source(1,"BALANCE","1010","MATERIAL","2","20","2026-10-08");
        start(List.of(first));accept(List.of(first));complete();
        var next=source(1,"BALANCE","1010","MATERIAL","3","30","2026-10-09");
        UUID successor=UUID.randomUUID();
        var command=new Start(successor,"f".repeat(64),organization,snapshot.plusDays(1),
            Map.of("BALANCE",1L,"MOVEMENT",0L),chain(List.of(next)));
        tx.execute(status->importer.start(tenant,company,command));
        tx.execute(status->importer.chunk(tenant,company,successor,new Chunk(List.of(next))));
        tx.execute(status->importer.complete(tenant,company,successor));
        assertThat(importer.status(tenant,company,successor)).containsEntry("status","COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from warehouse_account_imports",Integer.class)).isEqualTo(2);
        assertThat(query.snapshotDate()).isEqualTo(snapshot.plusDays(1));
        var latest=reports.generate(filter(Type.STOCK_BALANCES,"2026-10-09","2026-10-09","1010",null),true);
        assertThat(latest.content()).hasSize(1);
        exact(latest.content().getFirst().closingQuantity(),"3");
    }
    @Test void materialCardMatchesValueOnlyAccountAcrossSourceHistoryAndSnapshotReplacement() throws Exception {
        var first = List.of(
            source(1,"BALANCE","1010","MATERIAL","0","197852.94","2026-10-08"),
            source(2,"MOVEMENT","1010","MATERIAL","-1","-10","2026-10-07"));
        start(first); accept(first); complete();

        var historical = reports.materialCard(material, warehouse, LocalDate.parse("2026-10-07"),
            LocalDate.parse("2026-10-07"), "1010", 0, 25);
        exact(historical.openingQuantity(), "1");
        exact(historical.openingAmount(), "197862.94");
        exact(historical.closingQuantity(), "0");
        exact(historical.closingAmount(), "197852.94");

        var next = source(1,"BALANCE","1010","MATERIAL","0","197852.94","2026-10-09");
        UUID successor = UUID.randomUUID();
        tx.execute(status -> importer.start(tenant, company, new Start(successor,"f".repeat(64),organization,
            snapshot.plusDays(1), Map.of("BALANCE",1L,"MOVEMENT",0L),uncheckedChain(List.of(next)))));
        tx.execute(status -> importer.chunk(tenant, company, successor, new Chunk(List.of(next))));
        tx.execute(status -> importer.complete(tenant, company, successor));

        var report = reports.generate(new Filter(Type.STOCK_BALANCES, snapshot.plusDays(1), snapshot.plusDays(1),
            warehouse, material, null, null, 0, 25, null, null, "1010", null), false);
        var card = reports.materialCard(material, warehouse, snapshot.plusDays(1), snapshot.plusDays(1), "1010", 0, 25);
        assertThat(report.content()).hasSize(1);
        exact(card.closingQuantity(), report.content().getFirst().closingQuantity());
        exact(card.closingAmount(), report.content().getFirst().closingAmount());
        exact(card.closingQuantity(), "0");
        exact(card.closingAmount(), "197852.94");
    }
    @Test void sourceSnapshotAndHistoryReplaceOldOpeningOnceAndPreserveLaterMmChanges()throws Exception {
        var rows=List.of(source(1,"BALANCE","1010","MATERIAL","100.0000001","1000.0123","2026-10-08"),
            source(2,"MOVEMENT","1010","MATERIAL","120.0000001","1200.0123","2026-09-01"),source(3,"MOVEMENT","1010","MATERIAL","-20","-200","2026-09-10"));
        start(rows);accept(rows);complete();
        post("2026-10-08","RECEIPT","100.0000001","1000.0123","OPENING_STOCK_EXACT");post("2026-10-09","ISSUE","4","40",null);
        jdbc.update("insert into warehouse_imported_movements(id,tenant_id,company_id,movement_date,quantity,amount,material_id,warehouse_id) values(?,?,?,date '2026-09-01',120,1200,?,?)",UUID.randomUUID(),tenant,company,material,warehouse);
        var historical=reports.generate(filter(Type.MATERIAL_STATEMENT,"2026-09-01","2026-09-30","1010",null),true).content().getFirst();
        exact(historical.openingQuantity(),"0");exact(historical.receiptQuantity(),"120.0000001");exact(historical.issueQuantity(),"20");exact(historical.closingQuantity(),"100.0000001");
        var current=reports.generate(filter(Type.STOCK_BALANCES,"2026-10-09","2026-10-09",null,null),true);
        exact(current.content().stream().filter(r->r.accountCode().equals("1010")).findFirst().orElseThrow().closingQuantity(),"100.0000001");
        exact(current.content().stream().filter(r->r.accountCode().equals("—")).findFirst().orElseThrow().closingQuantity(),"-4");
        exact(current.totals().stream().map(Total::closingQuantity).map(BigDecimal::new).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString(),"96.0000001");
        exact(reports.generate(filter(Type.RECEIPTS,"2026-09-01","2026-09-30","1010",null),true).content().getFirst().receiptQuantity(),"120.0000001");
        exact(reports.generate(filter(Type.ISSUES,"2026-09-01","2026-09-30","1010",null),true).content().getFirst().issueQuantity(),"20");
    }
    void post(String date,String type,String quantity,String amount,String reference) {
        jdbc.update("insert into stock_movements(id,tenant_id,company_id,material_id,warehouse_id,movement_date,posted_at,status,reference_type,movement_type,quantity,total_cost) values(?,?,?,?,?,?,CURRENT_TIMESTAMP,'POSTED',?,?,?,?)",UUID.randomUUID(),tenant,company,material,warehouse,LocalDate.parse(date),reference,type,new BigDecimal(quantity),new BigDecimal(amount));
    }
    @Test void manualReceiptAppearsUnderItsSavedAccount() throws Exception {
        var rows = List.of(source(1,"BALANCE","1010","MATERIAL","10","100","2026-10-08"));
        start(rows); accept(rows); complete();
        post("2026-10-09","RECEIPT","2","20","MANUAL_WAREHOUSE_FACT");
        jdbc.update("update stock_movements set account_code_snapshot='1010' where reference_type='MANUAL_WAREHOUSE_FACT'");
        var result = reports.generate(filter(Type.STOCK_BALANCES,"2026-10-09","2026-10-09","1010",null),true);
        assertThat(result.content()).allMatch(row -> "1010".equals(row.accountCode()));
        exact(result.content().stream().map(WarehouseReport.Row::receiptQuantity).map(BigDecimal::new).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString(),"2");
    }
    @Test void fullAccountHistoryRemainsUsableAtProductionVolume()throws Exception {
        var one=source(1,"BALANCE","1010","MATERIAL","1.0000001","10.0123","2026-10-08");start(List.of(one));accept(List.of(one));complete();
        // Real cardinality: 60k stock keys and one million source movements. Empty
        // master links also exercise products and historical source references.
        jdbc.execute("""
            insert into warehouse_account_entries
            select md5('scale-balance:'||n)::uuid,tenant_id,company_id,import_id,n+1,'scale-balance:'||n,kind,source_date,
              account_source_id,account_code,account_name,material_reference_type,md5('scale-material:'||n),warehouse_source_id,
              null,warehouse_id,'S'||n,material_name,warehouse_code,warehouse_name,source_type,source_material_type,stock_category,
              uom,uom_name_ru,quantity,amount,corresponding_account,counterparty,document_key,additional_dimensions,row_sha256,content
            from warehouse_account_entries cross join generate_series(1,60000) n where source_seq=1
            """);
        jdbc.execute("""
            insert into warehouse_account_entries
            select md5('scale-history:'||n)::uuid,tenant_id,company_id,import_id,n+60001,'scale-history:'||n,'MOVEMENT',date '2020-01-01'+(n%2000),
              account_source_id,account_code,account_name,material_reference_type,md5('scale-material:'||(n%60000+1)),warehouse_source_id,
              null,warehouse_id,'S'||(n%60000+1),material_name,warehouse_code,warehouse_name,source_type,source_material_type,stock_category,
              uom,uom_name_ru,case when n%2=0 then 1 else -1 end,case when n%2=0 then 10 else -10 end,
              corresponding_account,counterparty,'document:scale:'||n,additional_dimensions,row_sha256,content
            from warehouse_account_entries cross join generate_series(1,1000000) n where source_seq=1
            """);
        jdbc.execute("ANALYZE warehouse_account_entries");
        jdbc.execute("alter role "+postgres.getUsername()+" set work_mem='1048kB'");
        jdbc.execute("alter role "+postgres.getUsername()+" set statement_timeout='15s'");
        try {
            long start=System.nanoTime();
            var report=reports.generate(new Filter(Type.STOCK_BALANCES,snapshot,snapshot,null,null,null,null,0,1),false);
            assertThat(report.totalElements()).isEqualTo(60001);assertThat(report.content()).hasSize(1);
            exact(report.totals().getFirst().closingQuantity(),"60001.0060001");
            System.out.println("Account snapshot report milliseconds="+(System.nanoTime()-start)/1_000_000);
            start=System.nanoTime();
            var history=reports.generate(filter(Type.MATERIAL_STATEMENT,"2020-01-01","2026-10-08",null,null),false);
            assertThat(history.totalElements()).isEqualTo(60001);
            exact(history.totals().getFirst().closingQuantity(),"60001.0060001");
            System.out.println("Million-movement account report milliseconds="+(System.nanoTime()-start)/1_000_000);
            start=System.nanoTime();
            var card=reports.materialCard(material,warehouse,snapshot,snapshot,"1010",0,25);
            exact(card.closingQuantity(),"1.0000001");
            exact(card.closingAmount(),"10.0123");
            System.out.println("Million-movement material card milliseconds="+(System.nanoTime()-start)/1_000_000);
        } finally {
            jdbc.execute("alter role "+postgres.getUsername()+" reset work_mem");jdbc.execute("alter role "+postgres.getUsername()+" reset statement_timeout");
        }
    }
    @Test void corruptedContentOrTypedValuePreventsCompletion()throws Exception {
        var rows=List.of(source(1,"BALANCE","0820","FIXED_ASSET","1.0000001","0","2026-10-08"));start(rows);accept(rows);
        jdbc.update("update warehouse_account_entries set quantity=2");assertThatThrownBy(this::complete).isInstanceOf(ConflictException.class);assertThat(query.available()).isFalse();
        jdbc.update("update warehouse_account_entries set quantity=1.0000001,content=content||' '");assertThatThrownBy(this::complete).isInstanceOf(ConflictException.class);
    }
    @Test void changedChunkDuplicateIdentityWrongScopeAndWrongSourceLinksLeaveNoPartialWrites()throws Exception {
        var one=source(1,"BALANCE","0820","FIXED_ASSET","1","0","2026-10-08");var two=source(2,"BALANCE","002","MATERIAL","2","0","2026-10-08");
        start(List.of(one,two));accept(List.of(one));
        assertThatThrownBy(()->accept(List.of(source(1,"BALANCE","0820","FIXED_ASSET","999","0","2026-10-08")))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->accept(List.of(new WarehouseAccountImportDtos.Row(2,one.sha256(),one.content())))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        CompanyContext.setCompany(UUID.randomUUID());assertThatThrownBy(()->importer.status(tenant,CompanyContext.currentCompany(),runId)).isInstanceOf(BadRequestException.class);CompanyContext.setCompany(company);
        jdbc.update("update materials set description='Unrelated material'");assertThatThrownBy(()->accept(List.of(two))).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_account_entries",Long.class)).isEqualTo(1);
        assertThatThrownBy(this::complete).isInstanceOf(ConflictException.class);
    }
    com.fasterxml.jackson.databind.node.ObjectNode profile(String category,String table,String source,UUID target,UUID parent) {
        var r=mapper.createObjectNode().put("kind","SCHEMA").put("sourceCategory",category).put("sourceTable",table)
            .put("sourceKey","source:"+category+":"+table+":"+source).put("targetId",target.toString());
        var d=r.putObject("data").put("sourceId",source);d.putObject("raw").put("_idrref",source);
        var v=d.putObject("view").put("name","Исходное наименование").put("sourceType","GOODS").put("sourceKind","Товар");
        if(parent!=null)v.put("parentId",parent.toString());return r;
    }
    void project(List<com.fasterxml.jackson.databind.JsonNode> rows) {
        UUID history=UUID.randomUUID();
        jdbc.update("insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,expected_chain,chain_sha256,status) values(?,?,?,?,?,'{}',?, ?,?,'COMPLETED')",history,tenant,company,history.toString().replace("-","")+"0".repeat(32),organization,rows.size(),"0".repeat(64),"0".repeat(64));
        long seq=0;for(var r:rows){String key=r.path("sourceKey").asText();UUID id=UUID.nameUUIDFromBytes((tenant+":"+company+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,row_sha256,content) values(?,?,?,?,?,'SCHEMA',?,?,?,?)",id,tenant,company,history,++seq,key,r.path("sourceTable").asText(),WarehouseAccountImportService.sha(r.toString()),r.toString());}
        new com.enterprise.mm.warehouse.history.WarehouseSourceProfileProjection(jdbc,mapper).apply(tenant,company,rows);
    }
    @Test void sourceBindingCorrectsCatalog55ClassificationWithoutRewritingEvidence() throws Exception {
        var row=source(1,"BALANCE","2910","FINISHED_GOODS","2.0000001","15.45","2026-10-08");start(List.of(row));accept(List.of(row));complete();
        String before=jdbc.queryForObject("select content from warehouse_account_entries",String.class);
        UUID product=UUID.randomUUID();jdbc.update("insert into materials(id,tenant_id,company_id,code,name,description) values(?,?,?,?,?,?)",product,tenant,company,"P55","Продукт","[1C:navoiyazot83:Catalog55:"+materialSource+"]");
        tx.execute(status->{project(List.of(profile("MATERIAL_DETAILS","_reference55",materialSource,product,null)));return null;});
        var report=reports.generate(filter(Type.STOCK_BALANCES,"2026-10-08","2026-10-08","2910","GOODS"),false);
        assertThat(report.content()).hasSize(1);assertThat(report.content().getFirst().materialId()).isEqualTo(product);
        assertThat(jdbc.queryForObject("select content from warehouse_account_entries",String.class)).isEqualTo(before);
        var service=new com.enterprise.mm.warehouse.history.WarehouseSourceProfileService(new NamedParameterJdbcTemplate(jdbc),mapper,mock(RoleDataScopeService.class));
        assertThat(service.records("MATERIAL_DETAILS",product,null,null,0,50,false)).containsEntry("totalElements",1L);
        assertThat(service.records("BALANCE_DETAILS",null,"2910",null,0,50,false)).containsEntry("totalElements",1L);
        var warehouseAccess=mock(RoleDataScopeService.class);when(warehouseAccess.effectiveWarehouseId(null)).thenReturn(warehouse);
        var scoped=new com.enterprise.mm.warehouse.history.WarehouseSourceProfileService(new NamedParameterJdbcTemplate(jdbc),mapper,warehouseAccess);
        assertThat(scoped.records("MATERIAL_DETAILS",product,null,null,0,50,false)).containsEntry("totalElements",1L);
        var proof=new com.enterprise.mm.warehouse.history.WarehouseSourceVerification(new NamedParameterJdbcTemplate(jdbc)).verify();
        assertThat(proof).containsEntry("stockEffects",false);
    }
    @Test void sourceHierarchyRejectsCyclesAndForeignCatalogBindingsAtomically() {
        UUID other=UUID.randomUUID();String otherSource="f".repeat(32);
        jdbc.update("insert into warehouses(id,tenant_id,company_id,code,name,description) values(?,?,?,?,?,?)",other,tenant,company,"P","Группа","[1C:navoiyazot83:Catalog85:"+otherSource+"]");
        assertThatThrownBy(()->tx.execute(status->{project(List.of(profile("WAREHOUSE_DETAILS","_reference85",warehouseSource,warehouse,other),profile("WAREHOUSE_DETAILS","_reference85",otherSource,other,warehouse)));return null;})).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from warehouses where parent_id is not null",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from warehouse_source_profiles",Long.class)).isZero();
        assertThatThrownBy(()->tx.execute(status->{project(List.of(profile("WAREHOUSE_DETAILS","_reference85",otherSource,warehouse,null)));return null;})).isInstanceOf(BadRequestException.class);
    }
    @Test void sourceCsvEscapesSpreadsheetCommandsAndPreservesNegativeDecimals() {
        var exporter=new com.enterprise.mm.warehouse.history.WarehouseSourceProfileExport();
        var data=Map.<String,Object>of("content",List.of(Map.<String,Object>of("code","0001","name","=HYPERLINK(1)","uomNameRu","шт.","quantity","-0.0000001","amount","12.3456","sourceId","a".repeat(32))));
        String csv=new String(exporter.export(data,"MATERIAL_DETAILS","csv","ru"),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF").contains("Наименование","Количество","' =".replace(" ",""),"Единица измерения (1С)","шт.","-0.0000001","0001","12.3456");
    }

}

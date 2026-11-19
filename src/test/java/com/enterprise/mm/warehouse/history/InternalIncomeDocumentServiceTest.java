package com.enterprise.mm.warehouse.history;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.service.RoleDataScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class InternalIncomeDocumentServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    final UUID company = UUID.randomUUID(), source = UUID.randomUUID(), destination = UUID.randomUUID(), other = UUID.randomUUID();
    final ObjectMapper mapper = new ObjectMapper();
    JdbcTemplate jdbc;
    InternalIncomeDocumentService service;
    RoleDataScopeService roles;
    UUID run;
    long sequence;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop schema public cascade; create schema public");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__warehouse_source_history.sql")).execute(ds);
        jdbc.execute("""
            create table warehouses (id uuid primary key, tenant_id text, company_id uuid, name text);
            create table materials (id uuid primary key, tenant_id text, company_id uuid, code text, name text);
            create table unit_of_measures (id uuid primary key, tenant_id text, company_id uuid, code text, name text, measurement_name_short text);
            """);
        for (var wh : Map.of(source, "Sending warehouse", destination, "Receiving warehouse", other, "Other warehouse").entrySet())
            jdbc.update("insert into warehouses values (?,'test',?,?)", wh.getKey(), company, wh.getValue());
        run = UUID.randomUUID(); sequence = 0;
        jdbc.update("""
            insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,
                expected_counts,expected_records,expected_chain,chain_sha256,status)
            values (?,'test',?,'hash','org','{}',100,'hash','hash','COMPLETED')
            """, run, company);
        roles = mock(RoleDataScopeService.class);
        when(roles.availabilityWarehouseId(any())).thenAnswer(i -> i.getArgument(0));
        service = new InternalIncomeDocumentService(new NamedParameterJdbcTemplate(ds), roles);
        TenantContext.setTenant("test"); CompanyContext.setCompany(company);
    }

    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void incomingDocumentsFollowTheHeaderOrTableDestinationSwitch() throws Exception {
        UUID header = document("_document162", "HEADER", source, destination, false);
        line(header, "_document162", other, "1", "HEADER-REQ", "1.0000001"); // Stale line destination ignored.
        UUID split = document("_document162", "SPLIT", source, other, true);
        line(split, "_document162", destination, "1", "OWN-REQ", "2.0000000");
        line(split, "_document162", other, "2", "PRIVATE-REQ", "3");
        line(split, "_document162", null, "3", "UNMAPPED-REQ", "4");
        UUID unmapped = document("_document162", "UNMAPPED", source, destination, true);
        line(unmapped, "_document162", null, "1", "HIDDEN", "5");

        assertThat(service.documents(destination, null, 0, 20).content()).extracting(InternalIncomeDocumentService.Document::id)
            .containsExactlyInAnyOrder(header, split);
        var detail = service.detail(split, destination);
        assertThat(detail.document().lineCount()).isEqualTo(1);
        assertThat(detail.document().requirements()).containsExactly("OWN-REQ");
        assertThat(detail.lines()).hasSize(1);
        assertThat(detail.lines().getFirst().quantity()).isEqualTo("2.0000000");
        assertThat(service.documents(destination, "PRIVATE-REQ", 0, 20).totalElements()).isZero();
        assertThat(service.documents(destination, "UNMAPPED-REQ", 0, 20).totalElements()).isZero();
        assertThat(service.documents(source, null, 0, 20).totalElements()).isZero();
        assertThatThrownBy(() -> service.detail(header, source)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.detail(unmapped, destination)).isInstanceOf(NotFoundException.class);
    }

    @Test void internalReceiptPreservesRequirementExactQuantityAndOriginalUnitWithoutInventingASource() throws Exception {
        UUID doc = document("_document182", "INCOME", destination, null, false);
        line(doc, "_document182", null, "10", "233-037.3", "123456789012345.0000001");
        line(doc, "_document182", null, "2", "233-037.3", "0.0000001");
        jdbc.update("insert into unit_of_measures values (?,'test',?,'1C-UOM-unit-source','Kilogram','kg')", UUID.randomUUID(), company);
        reference("reference:materials:material-source", "Archived material");
        var detail = service.detail(doc, destination);
        assertThat(detail.historyOnly()).isTrue();
        assertThat(detail.document().sourceWarehouseName()).isNull();
        assertThat(detail.document().destinationWarehouseName()).isEqualTo("Receiving warehouse");
        assertThat(detail.document().requirements()).containsExactly("233-037.3");
        assertThat(detail.lines()).extracting(InternalIncomeDocumentService.Line::lineNumber).containsExactly("2", "10");
        assertThat(detail.lines().getLast().quantity()).isEqualTo("123456789012345.0000001");
        assertThat(detail.lines().getFirst().materialName()).isEqualTo("Archived material");
        assertThat(detail.lines().getFirst().unit()).isEqualTo("kg");
    }

    @Test void bothDocumentAndLineImportsMustBeCompletedAndScopeMustMatch() throws Exception {
        UUID doc = document("_document162", "SCOPED", source, destination, false);
        line(doc, "_document162", null, "1", "REQ", "1");
        assertThat(service.documents(destination, null, 0, 20).totalElements()).isEqualTo(1);
        CompanyContext.setCompany(UUID.randomUUID());
        assertThat(service.documents(destination, null, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.detail(doc, destination)).isInstanceOf(NotFoundException.class);
        CompanyContext.setCompany(company); TenantContext.setTenant("other");
        assertThat(service.documents(destination, null, 0, 20).content()).isEmpty();
        TenantContext.setTenant("test");
        jdbc.update("update warehouse_history_runs set status='IMPORTING' where id=?", run);
        assertThat(service.documents(destination, null, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.detail(doc, destination)).isInstanceOf(NotFoundException.class);
        jdbc.update("update warehouse_history_runs set status='COMPLETED' where id=?", run);
        UUID incomplete = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,
                expected_counts,expected_records,expected_chain,chain_sha256,status)
            values (?,'test',?,'incomplete','org','{}',100,'hash','hash','IMPORTING')
            """, incomplete, company);
        jdbc.update("update warehouse_history_records set import_id=? where kind='LINE'", incomplete);
        assertThat(service.documents(destination, null, 0, 20).content()).isEmpty();
    }

    @Test void searchesNumbersAndRequirementsLiterallyAndPaginates() throws Exception {
        UUID first = document("_document162", "DOC-2026", source, destination, false);
        line(first, "_document162", null, "1", "REQ_%", "1");
        UUID second = document("_document182", "OTHER", destination, null, false);
        line(second, "_document182", null, "1", "REQ-OTHER", "2");
        assertThat(service.documents(destination, "doc-2026", 0, 20).content()).extracting(InternalIncomeDocumentService.Document::id).containsExactly(first);
        assertThat(service.documents(destination, "_%", 0, 20).content()).extracting(InternalIncomeDocumentService.Document::id).containsExactly(first);
        var p1 = service.documents(destination, "req", 0, 1);
        var p2 = service.documents(destination, "req", 1, 1);
        assertThat(p1.totalElements()).isEqualTo(2);
        assertThat(p1.totalPages()).isEqualTo(2);
        assertThat(p1.content().getFirst().id()).isNotEqualTo(p2.content().getFirst().id());
        assertThatThrownBy(() -> service.documents(destination, null, -1, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.documents(destination, null, 0, 101)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.documents(destination, "a".repeat(121), 0, 20)).isInstanceOf(BadRequestException.class);
    }

    @Test void unpostedAndDeletedSnapshotDocumentsRemainHistoricalAndReadsDoNotMutateEvidence() throws Exception {
        UUID doc = document("_document162", "OLD-DRAFT", source, destination, false);
        line(doc, "_document162", null, "1", "REQ", "1");
        jdbc.update("update warehouse_history_records set posted=false,marked=true where id=?", doc);
        var before = jdbc.queryForList("select * from warehouse_history_records order by source_seq");
        var detail = service.detail(doc, destination);
        assertThat(detail.document().posted()).isFalse();
        assertThat(detail.document().marked()).isTrue();
        assertThat(detail.historyOnly()).isTrue();
        service.documents(destination, null, 0, 20);
        assertThat(jdbc.queryForList("select * from warehouse_history_records order by source_seq")).isEqualTo(before);
        // No operational posting tables are installed in this test: any posting attempt fails.
    }

    @Test void requiresReceivingWarehouseAndHonorsAssignmentDenial() {
        assertThatThrownBy(() -> service.documents(null, null, 0, 20)).isInstanceOf(BadRequestException.class);
        when(roles.availabilityWarehouseId(destination)).thenThrow(new AccessDeniedException("Wrong assignment"));
        assertThatThrownBy(() -> service.documents(destination, null, 0, 20)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.detail(UUID.randomUUID(), destination)).isInstanceOf(AccessDeniedException.class);
    }

    private UUID document(String table, String number, UUID warehouse, UUID target, boolean byLine) throws Exception {
        UUID id = UUID.randomUUID();
        insert(id, "DOCUMENT", table, id.toString(), number, warehouse, target, null,
            mapper.writeValueAsString(Map.of("data", Map.of("_fld5251", byLine))));
        return id;
    }

    private void line(UUID doc, String table, UUID target, String lineNumber, String requirement, String quantity) throws Exception {
        boolean transfer = table.equals("_document162");
        insert(UUID.randomUUID(), "LINE", transfer ? "_document162_vt2001" : "_document182_vt2509", doc.toString(), null, source, target, quantity,
            mapper.writeValueAsString(Map.of("lineNumber", lineNumber, "materialSourceId", "material-source", "data",
                Map.of(transfer ? "_fld5253" : "_fld13862", requirement, transfer ? "_fld2007rref" : "_fld2515rref", "unit-source"))));
    }

    private void reference(String key, String name) throws Exception {
        UUID id = UUID.randomUUID();
        insert(id, "SCHEMA", "_reference54", null, null, null, null, null, mapper.writeValueAsString(Map.of("data", Map.of("name", name))));
        jdbc.update("update warehouse_history_records set source_key=? where id=?", key, id);
    }

    private void insert(UUID id, String kind, String table, String document, String number, UUID warehouse, UUID target, String quantity, String content) {
        jdbc.update("""
            insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,
                document_key,document_type,document_number,document_date,warehouse_id,target_warehouse_id,quantity,
                posted,marked,row_sha256,content)
            values (?,'test',?,?,?,?,?,?,?,'Internal document',?,timestamp '2026-10-07 12:00:00',?,?,?,true,false,'hash',?)
            """, id, company, run, ++sequence, kind, id.toString(), table, document, number, warehouse, target, quantity, content);
    }
}

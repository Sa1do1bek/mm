package com.enterprise.mm.warehouse.history;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.TransferCreateRequest;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import com.enterprise.mm.service.transfer.TransferActionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IssueSourceDocumentServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    final UUID company = UUID.randomUUID(), source = UUID.randomUUID(), destination = UUID.randomUUID(), materialId = UUID.randomUUID(), unitId = UUID.randomUUID();
    final ObjectMapper mapper = new ObjectMapper();
    JdbcTemplate jdbc;
    IssueSourceDocumentService service;
    IssueSourcePreparationService preparation;
    IssueSourceTransferGuard guard;
    TransferDocumentService transfers;
    IdempotencyRecordRepository idempotencyRecords;
    RoleDataScopeService roles;
    TransactionTemplate tx;
    Material material;
    UUID run;
    long sequence;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop schema public cascade; create schema public");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__warehouse_source_history.sql")).execute(ds);
        jdbc.execute("""
            create table warehouses(id uuid primary key,tenant_id text,company_id uuid,name text,is_delete boolean default false);
            create table materials(id uuid primary key,tenant_id text,company_id uuid,code text,name text);
            create table unit_of_measures(id uuid primary key,tenant_id text,company_id uuid,code text,name text,measurement_name_short text,is_delete boolean default false);
            create table transfer_documents(id uuid primary key,tenant_id text,company_id uuid,status text,is_delete boolean default false);
            """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V48__warehouse_issue_source_links.sql")).execute(ds);
        for (var wh : Map.of(source, "Sending", destination, "Receiving").entrySet())
            jdbc.update("insert into warehouses(id,tenant_id,company_id,name) values (?,'test',?,?)", wh.getKey(), company, wh.getValue());
        jdbc.update("insert into materials values (?,'test',?,'MAT','Precise material')", materialId, company);
        jdbc.update("insert into unit_of_measures(id,tenant_id,company_id,code,name,measurement_name_short) values (?,'test',?,'1C-UOM-unit','Kilogram','kg')", unitId, company);
        run = UUID.randomUUID(); sequence = 0;
        jdbc.update("""
            insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,expected_chain,chain_sha256,status,completed_at)
            values (?,'test',?,'hash','org','{}',100,'hash','hash','COMPLETED',current_timestamp)
            """, run, company);
        roles = mock(RoleDataScopeService.class);
        when(roles.availabilityWarehouseId(any())).thenAnswer(i -> i.getArgument(0));
        when(roles.canAccessWarehouse(source)).thenReturn(true);
        material = new Material(); material.setId(materialId); material.setTenantId("test"); material.setCompanyId(company); material.setUnitOfMeasureId(unitId);
        var unit = new UnitOfMeasure(); unit.setId(unitId); unit.setCode("1C-UOM-unit"); unit.setStockQuantityScale(7); material.setUnitOfMeasure(unit);
        var materials = mock(MaterialRepository.class);
        when(materials.findByIdAndIsDeletedFalse(any())).thenAnswer(i -> materialId.equals(i.getArgument(0)) ? Optional.of(material) : Optional.empty());
        var named = new NamedParameterJdbcTemplate(ds);
        service = new IssueSourceDocumentService(named, roles, materials);
        guard = new IssueSourceTransferGuard(named);
        transfers = mock(TransferDocumentService.class);
        when(transfers.create(any())).thenAnswer(i -> {
            var result = new TransferDocumentResponse(); result.setId(UUID.randomUUID()); result.setCode("TR-TEST"); result.setStatus("DRAFT");
            jdbc.update("insert into transfer_documents(id,tenant_id,company_id,status) values (?,'test',?,'DRAFT')", result.getId(), company);
            return result;
        });
        idempotencyRecords = mock(IdempotencyRecordRepository.class);
        Map<String, IdempotencyRecord> saved = new ConcurrentHashMap<>();
        when(idempotencyRecords.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(any(), any(), any(), any(), any()))
            .thenAnswer(i -> Optional.ofNullable(saved.get(i.getArgument(3) + ":" + i.getArgument(4))));
        when(idempotencyRecords.save(any())).thenAnswer(i -> { IdempotencyRecord record = i.getArgument(0); saved.put(record.getResourceId() + ":" + record.getIdempotencyKey(), record); return record; });
        preparation = new IssueSourcePreparationService(named, service, transfers, mock(TransferDocumentRepository.class),
            new TransferActionPolicy(mock(TransferDocumentRepository.class), roles), new IdempotencyService(idempotencyRecords, mapper));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        actor("WAREHOUSE_MANAGER");
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }
    void actor(String role) {
        TenantContext.setTenant("test"); CompanyContext.setCompany(company);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reviewer", "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test void listsReviewAndHistorySeparatelyWithExactLinesAndLiteralSearch() throws Exception {
        UUID review = document("_document4470", "REQ-2026", false, false);
        line(review, "_document4470_vt4512", "10", "REQ_%", "123456789012345.0000001", destination);
        line(review, "_document4470_vt4512", "2", "REQ_%", "0.0000001", destination);
        document("_document4470", "POSTED", true, false);
        document("_document4470", "DELETED", false, true);
        document("_document199", "WRITE-OFF", false, false);
        assertThat(service.documents(source, "review", "_%", 0, 20).content()).extracting(IssueSourceDocumentService.Document::id).containsExactly(review);
        assertThat(service.documents(source, "history", null, 0, 20).totalElements()).isEqualTo(3);
        var detail = service.detail(review, source);
        assertThat(detail.canPrepare()).isTrue();
        assertThat(detail.lines()).extracting(IssueSourceDocumentService.Line::lineNumber).containsExactly("2", "10");
        assertThat(detail.lines().getLast().quantity()).isEqualTo("123456789012345.0000001");
        assertThat(detail.lines().getLast().destinationWarehouseName()).isEqualTo("Receiving");
        assertThat(detail.lines().getFirst().unit()).isEqualTo("kg");
        assertThat(service.documents(source, "history", null, 1, 1).content()).hasSize(1);
        assertThatThrownBy(() -> service.documents(source, "x", null, 0, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.documents(source, "review", null, -1, 20)).isInstanceOf(BadRequestException.class);
    }

    @Test void scopeAssignmentAndImportCompletionCannotBeBypassed() throws Exception {
        UUID doc = validRequest();
        assertThatThrownBy(() -> service.detail(doc, destination)).isInstanceOf(NotFoundException.class);
        CompanyContext.setCompany(UUID.randomUUID());
        assertThat(service.documents(source, "review", null, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.detail(doc, source)).isInstanceOf(NotFoundException.class);
        actor("WAREHOUSE_MANAGER"); TenantContext.setTenant("other");
        assertThat(service.documents(source, "review", null, 0, 20).content()).isEmpty();
        actor("WAREHOUSE_MANAGER");
        when(roles.availabilityWarehouseId(destination)).thenThrow(new AccessDeniedException("Assignment"));
        assertThatThrownBy(() -> service.detail(doc, destination)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("update warehouse_history_runs set status='IMPORTING'");
        assertThat(service.documents(source, "review", null, 0, 20).content()).isEmpty();
    }

    @Test void completedHeaderWithIncompleteLineImportRemainsBlocked() throws Exception {
        UUID doc = validRequest();
        UUID pending = UUID.randomUUID();
        jdbc.update("insert into warehouse_history_runs select ?,tenant_id,company_id,'pending',organization_source_id,expected_counts,expected_records,expected_chain,accepted_records,chain_sha256,'IMPORTING',created_at,null,created_by from warehouse_history_runs where id=?", pending, run);
        jdbc.update("update warehouse_history_records set import_id=? where kind='LINE'", pending);
        assertThat(codes(service.detail(doc, source))).contains("NO_LINES", "IMPORT_INCOMPLETE");
    }

    @Test void refusesSourceMovementsRelatedPostedRequestsAndUnsafeMappings() throws Exception {
        UUID doc = validRequest();
        insert(UUID.randomUUID(), "MOVEMENT", "_accumrg", doc.toString(), null, "{}", "1", null, null);
        assertThat(codes(service.detail(doc, source))).contains("MOVEMENTS_EXIST");
        jdbc.update("delete from warehouse_history_records where kind='MOVEMENT'");
        UUID fulfilled = document("_document162", "FULFILLED", true, false);
        line(fulfilled, "_document162_vt2001", "1", "REQ_%", "1", destination);
        assertThat(codes(service.detail(doc, source))).contains("RELATED_POSTED_DOCUMENT");
        jdbc.update("update warehouse_history_records set posted=false where id=?", fulfilled);
        jdbc.update("update warehouse_history_records set target_warehouse_id=null,quantity='0',content=jsonb_set(content::jsonb,'{data,_fld4518}','\"2\"')::text where document_key=? and kind='LINE'", doc.toString());
        assertThat(codes(service.detail(doc, source))).contains("DESTINATION_UNRESOLVED", "UNIT_MISMATCH", "INVALID_QUANTITY");
        material.setCompanyId(UUID.randomUUID());
        assertThat(codes(service.detail(doc, source))).contains("MATERIAL_UNAVAILABLE");
    }

    @Test void postedAndDeletedDocumentsNeverPrepareAndWarehouseOperatorsOnlyReview() throws Exception {
        UUID doc = validRequest();
        actor("WAREHOUSE_OPERATOR");
        assertThat(service.detail(doc, source).canPrepare()).isFalse();
        assertThatThrownBy(() -> prepare(doc, request(doc), "operator")).isInstanceOf(AccessDeniedException.class);
        actor("WAREHOUSE_MANAGER");
        jdbc.update("update warehouse_history_records set posted=true,marked=true where id=?", doc);
        assertThat(codes(service.detail(doc, source))).contains("POSTED_OR_UNKNOWN", "DELETED_OR_UNKNOWN");
        assertThatThrownBy(() -> prepare(doc, request(doc), "posted")).isInstanceOf(ConflictException.class);
        verifyNoInteractions(transfers);
    }

    @Test void createsOneAuditedDraftWithoutChangingHistoryAndReplaysLostResponses() throws Exception {
        UUID doc = validRequest(); var request = request(doc);
        var before = jdbc.queryForList("select * from warehouse_history_records order by source_seq");
        var result = prepare(doc, request, "retry-key");
        assertThat(prepare(doc, request, "retry-key")).isEqualTo(result);
        assertThat(result.status()).isEqualTo("DRAFT");
        var captured = org.mockito.ArgumentCaptor.forClass(TransferCreateRequest.class);
        verify(transfers).create(captured.capture());
        assertThat(captured.getValue().getDestinationWarehouseId()).isEqualTo(destination);
        assertThat(captured.getValue().getLines().getFirst().getPlannedQuantity()).isEqualByComparingTo("2.0000001");
        var link = jdbc.queryForMap("select * from warehouse_issue_source_links where source_document_id=?", doc);
        assertThat(link.get("review_note")).isEqualTo("Checked current dispatch ledger");
        assertThat(link.get("reviewed_by")).isEqualTo("reviewer");
        assertThat(service.detail(doc, source).document().linkedDocumentId()).isEqualTo(result.documentId());
        assertThat(service.documents(source, "review", null, 0, 20).totalElements()).isEqualTo(1);
        jdbc.update("update transfer_documents set status='CLOSED' where id=?", result.documentId());
        assertThat(service.documents(source, "review", null, 0, 20).totalElements()).isZero();
        assertThat(service.documents(source, "history", null, 0, 20).totalElements()).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from warehouse_history_records order by source_seq")).isEqualTo(before);
        assertThatThrownBy(() -> prepare(doc, request, "second-key")).isInstanceOf(ConflictException.class);
    }

    @Test void invalidReviewStaleEvidenceAndChangedRetryCannotCreateAnotherDraft() throws Exception {
        UUID doc = validRequest(); var request = request(doc);
        assertThatThrownBy(() -> prepare(doc, new IssueSourcePreparationService.Request(source, request.fingerprint(), false, "note"), "false")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> prepare(doc, new IssueSourcePreparationService.Request(source, "0".repeat(64), true, "note"), "stale")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> prepare(doc, request, "")).isInstanceOf(BadRequestException.class);
        prepare(doc, request, "same");
        assertThatThrownBy(() -> prepare(doc, new IssueSourcePreparationService.Request(source, request.fingerprint(), true, "different"), "same")).isInstanceOf(ConflictException.class);
        verify(transfers, times(1)).create(any());
    }

    @Test void competingPreparersSerializeOnTheSourceAndCreateOnlyOneDraft() throws Exception {
        UUID doc = validRequest(); var request = request(doc);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> operation = () -> { actor("WAREHOUSE_MANAGER"); try { prepare(doc, request, UUID.randomUUID().toString()); return true; }
                catch (ConflictException expected) { return false; } finally { clear(); } };
            var results = executor.invokeAll(List.of(operation, operation));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("select count(*) from transfer_documents", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from warehouse_issue_source_links", Integer.class)).isEqualTo(1);
    }

    @Test void failureAfterDraftCreationRollsBackDraftAndSourceLink() throws Exception {
        UUID doc = validRequest(); var request = request(doc);
        doThrow(new IllegalStateException("Persistence unavailable")).when(idempotencyRecords).save(any());
        assertThatThrownBy(() -> prepare(doc, request, "rollback")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from transfer_documents", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from warehouse_issue_source_links", Integer.class)).isZero();
    }

    @Test void draftEditsCannotExceedAggregatedSourceQuantitiesOrChangeItsWarehouses() throws Exception {
        UUID doc = validRequest(); var prepared = prepare(doc, request(doc), "create");
        var edit = new TransferCreateRequest(); edit.setSourceWarehouseId(source); edit.setDestinationWarehouseId(destination);
        var first = new TransferCreateRequest.Line(); first.setMaterialId(materialId); first.setPlannedQuantity(new BigDecimal("1"));
        var second = new TransferCreateRequest.Line(); second.setMaterialId(materialId); second.setPlannedQuantity(new BigDecimal("1.0000001"));
        edit.setLines(List.of(first, second)); guard.validateEdit(prepared.documentId(), edit);
        second.setPlannedQuantity(new BigDecimal("1.0000002"));
        assertThatThrownBy(() -> guard.validateEdit(prepared.documentId(), edit)).isInstanceOf(ConflictException.class);
        second.setPlannedQuantity(BigDecimal.ONE); edit.setDestinationWarehouseId(UUID.randomUUID());
        assertThatThrownBy(() -> guard.validateEdit(prepared.documentId(), edit)).isInstanceOf(ConflictException.class);
        edit.setDestinationWarehouseId(destination); second.setMaterialId(UUID.randomUUID());
        assertThatThrownBy(() -> guard.validateEdit(prepared.documentId(), edit)).isInstanceOf(ConflictException.class);
        assertThatCode(() -> guard.validateEdit(UUID.randomUUID(), edit)).doesNotThrowAnyException();
    }

    List<String> codes(IssueSourceDocumentService.Detail detail) { assertThat(detail.canPrepare()).isFalse(); return detail.blockers().stream().map(IssueSourceDocumentService.Blocker::code).toList(); }
    IssueSourcePreparationService.Request request(UUID id) { return new IssueSourcePreparationService.Request(source, service.detail(id, source).fingerprint(), true, "Checked current dispatch ledger"); }
    IssueSourcePreparationService.Prepared prepare(UUID id, IssueSourcePreparationService.Request request, String key) { return tx.execute(s -> preparation.prepare(id, request, key)); }
    UUID validRequest() throws Exception {
        UUID doc = document("_document4470", "REQUEST", false, false);
        line(doc, "_document4470_vt4512", "1", "REQ_%", "2.0000001", destination); return doc;
    }
    UUID document(String table, String number, boolean posted, boolean marked) {
        UUID id = UUID.randomUUID(); insert(id, "DOCUMENT", table, id.toString(), number, "{}", null, posted, marked); return id;
    }
    void line(UUID doc, String table, String number, String requirement, String quantity, UUID target) throws Exception {
        UUID id = UUID.randomUUID(); insert(id, "LINE", table, doc.toString(), null,
            mapper.writeValueAsString(Map.of("lineNumber", number, "data", Map.of(table.equals("_document162_vt2001") ? "_fld5253" : "_fld4527", requirement,
                "_fld4517rref", "unit", "_fld4518", "1"))), quantity, null, null);
        jdbc.update("update warehouse_history_records set material_id=?,target_warehouse_id=? where id=?", materialId, target, id);
    }
    void insert(UUID id, String kind, String table, String document, String number, String content, String quantity, Boolean posted, Boolean marked) {
        jdbc.update("""
            insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,document_key,document_type,
                document_number,document_date,warehouse_id,quantity,posted,marked,row_sha256,content)
            values (?,'test',?,?,?,?,?,?,?,'1C request',?,timestamp '2026-10-07',?,?,?,?,?,?)
            """, id, company, run, ++sequence, kind, id.toString(), table, document, number, source, quantity, posted, marked, id.toString(), content);
    }
}

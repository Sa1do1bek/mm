package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.ReferenceMapping;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.service.StockMovementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"app.seed.enabled=false", "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
    "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false", "app.scheduling.enabled=false"})
@EntityScan(basePackages = {"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
@ActiveProfiles("test")
@Testcontainers
@Transactional
class KimyosanoatIssuePostingPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", PG::getJdbcUrl); p.add("spring.datasource.username", PG::getUsername);
        p.add("spring.datasource.password", PG::getPassword); p.add("spring.datasource.driver-class-name", PG::getDriverClassName);
    }
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    @Autowired StockMovementService movements;
    @Autowired StockBalanceRepository balances;
    @Autowired KimyosanoatIssueService issues;
    @Autowired JdbcTemplate jdbc;
    @MockBean KimyosanoatClient sourceClient;

    private <T extends BusinessEntity> T persist(T value, String name) {
        value.setTenantId("default"); value.setCompanyId(CompanyContext.currentCompany());
        value.setCode("KIMYO-" + UUID.randomUUID()); value.setName(name); value.setStatus("ACTIVE");
        em.persist(value); return value;
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear(); }

    @Test void issueUsesSelectedBatchAndRepeatingApprovalDoesNotReduceStockAgain() {
        TenantContext.setTenant("default"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var warehouse = persist(new Warehouse(), "Test warehouse");
        var unit = new UnitOfMeasure(); unit.setMeasurementNameShort("т"); persist(unit, "Tonnes");
        var material = new Material(); material.setMxikCode("02905001002000000"); material.setUnitOfMeasureId(unit.getId()); material.setUnitOfMeasure(unit); persist(material, "Test product");
        em.flush();
        var batch = new StockBatch(); batch.setWarehouseId(warehouse.getId()); batch.setMaterialId(material.getId()); batch.setBatchNumber("BATCH-1");
        persist(batch, "Batch one");
        var secondBatch = new StockBatch(); secondBatch.setWarehouseId(warehouse.getId()); secondBatch.setMaterialId(material.getId()); secondBatch.setBatchNumber("BATCH-2");
        persist(secondBatch, "Batch two"); em.flush();
        var receipt = new StockMovement(); receipt.setMaterialId(material.getId()); receipt.setWarehouseId(warehouse.getId());
        receipt.setStockBatchId(batch.getId()); receipt.setMovementType(MovementType.RECEIPT); receipt.setQuantity(new BigDecimal("10"));
        receipt.setUnitCost(new BigDecimal("5")); receipt.setMovementDate(LocalDate.now());
        movements.createAndPost(receipt);
        var secondReceipt = new StockMovement(); secondReceipt.setMaterialId(material.getId()); secondReceipt.setWarehouseId(warehouse.getId());
        secondReceipt.setStockBatchId(secondBatch.getId()); secondReceipt.setMovementType(MovementType.RECEIPT); secondReceipt.setQuantity(new BigDecimal("10"));
        secondReceipt.setUnitCost(new BigDecimal("9")); secondReceipt.setMovementDate(LocalDate.now());
        movements.createAndPost(secondReceipt); em.flush(); em.clear();
        var stock = balances.findByTenantIdAndCompanyIdAndWarehouseIdAndMaterialIdInAndIsDeletedFalse(
            "default", CompanyContext.currentCompany(), warehouse.getId(), List.of(material.getId()));
        UUID balanceId = stock.stream().filter(b -> secondBatch.getId().equals(b.getStockBatchId())).findFirst().orElseThrow().getId();
        UUID untouchedId = stock.stream().filter(b -> batch.getId().equals(b.getStockBatchId())).findFirst().orElseThrow().getId();
        var wh = new ReferenceMapping(); wh.setReferenceEntityType("WAREHOUSE"); wh.setReferenceId(warehouse.getId());
        wh.setExternalSystem("KIMYOSANOAT"); wh.setExternalCode("12"); persist(wh, "Warehouse mapping");
        UUID run = UUID.randomUUID(), record = UUID.randomUUID();
        jdbc.update("insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,expected_chain,chain_sha256,status) values(?,?,?,?,?,?,?,?,?,?)",
            run, "default", CompanyContext.currentCompany(), "a".repeat(64), "org", "{}", 1, "b".repeat(64), "c".repeat(64), "COMPLETED");
        jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,row_sha256,content) values(?,?,?,?,?,?,?,?,?,?)",
            record, "default", CompanyContext.currentCompany(), run, 1, "MASTER", "product", "_reference54", "d".repeat(64), "{}");
        jdbc.update("insert into warehouse_source_master_links(tenant_id,company_id,reference_kind,reference_type,source_id,master_id,source_record_id) values(?,?,?,?,?,?,?)",
            "default", CompanyContext.currentCompany(), "MATERIAL", "00000037", "8030001a4bcf769a11df9ed8671308d4", material.getId(), record);
        jdbc.update("insert into warehouse_source_profiles(id,tenant_id,company_id,category,source_table,source_id,target_id,code,name,search_text,fields) values(?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))",
            record, "default", CompanyContext.currentCompany(), "MATERIAL_DETAILS", "_reference55", "8030001a4bcf769a11df9ed8671308d4", material.getId(), "3203           ", "Test product", "test product", "{\"marked\":false,\"isGroup\":false}");
        var source = json.createObjectNode().put("id", 77).put("fromWarehouseId", 12).put("productId", 9)
            .put("productNCode", "3203")
            .put("lastStatus", "EXIT_ACCEPTED").put("confirmedNettoKg", 2000).put("measureUnit", "kg");
        source.putArray("confirmedStatuses").add("EXIT_ACCEPTED");
        var page = json.createObjectNode(); page.putArray("content").add(source); page.put("totalElements", 1).put("totalPages", 1);
        when(sourceClient.list(12, 0, 20)).thenReturn(page);
        when(sourceClient.detail(12, 77)).thenReturn(source);
        source.put("productNCode", "03203");
        assertThat(issues.list(warehouse.getId(), 0, 20).content().getFirst().state()).isEqualTo("MAPPING_REQUIRED");
        source.put("productNCode", "3203");
        jdbc.update("update warehouse_source_profiles set fields=cast(? as jsonb) where id=?", "{\"marked\":true,\"isGroup\":false}", record);
        assertThat(issues.list(warehouse.getId(), 0, 20).content().getFirst().state()).isEqualTo("MAPPING_REQUIRED");
        jdbc.update("update warehouse_source_profiles set fields=cast(? as jsonb) where id=?", "{\"marked\":false,\"isGroup\":false}", record);
        jdbc.update("update warehouse_source_master_links set master_id=? where source_record_id=?", UUID.randomUUID(), record);
        assertThat(issues.list(warehouse.getId(), 0, 20).content().getFirst().state()).isEqualTo("MAPPING_REQUIRED");
        jdbc.update("update warehouse_source_master_links set master_id=? where source_record_id=?", material.getId(), record);
        UUID duplicate = UUID.randomUUID();
        jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,row_sha256,content) values(?,?,?,?,?,?,?,?,?,?)",
            duplicate, "default", CompanyContext.currentCompany(), run, 2, "MASTER", "duplicate", "_reference55", "e".repeat(64), "{}");
        jdbc.update("insert into warehouse_source_profiles(id,tenant_id,company_id,category,source_table,source_id,target_id,code,name,search_text,fields) values(?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))",
            duplicate, "default", CompanyContext.currentCompany(), "MATERIAL_DETAILS", "_reference55", "9030001a4bcf769a11df9ed8671308d4", null, "3203           ", "Duplicate", "duplicate", "{\"marked\":false,\"isGroup\":false}");
        assertThat(issues.list(warehouse.getId(), 0, 20).content().getFirst().state()).isEqualTo("MAPPING_REQUIRED");
        jdbc.update("delete from warehouse_source_profiles where id=?", duplicate);
        var document = issues.list(warehouse.getId(), 0, 20).content().getFirst();
        assertThat(document.quantityStock()).isEqualTo("2");
        assertThat(document.stockUnit()).isEqualTo("t");
        source.put("productNCode", "unmatched").put("mxikCode", "2905001002000000");
        var changedDocument = issues.list(warehouse.getId(), 0, 20).content().getFirst();
        var candidates = issues.materialCandidates(warehouse.getId(), 77, changedDocument.matchFingerprint());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().materialId()).isEqualTo(material.getId());
        assertThat(candidates.getFirst().match()).isEqualTo("MXIK_NORMALIZED");
        var request = new KimyosanoatIssueService.ApproveRequest(warehouse.getId(), balanceId, candidates.getFirst().fingerprint(), material.getId());
        var posted = issues.approve(77, request); em.flush(); em.clear();
        assertThat(issues.list(warehouse.getId(), 0, 20).content().getFirst().state()).isEqualTo("POSTED");
        assertThat(balances.findById(balanceId).orElseThrow().getQuantity()).isEqualByComparingTo("8");
        assertThat(balances.findById(balanceId).orElseThrow().getTotalValue()).isEqualByComparingTo("72");
        assertThat(balances.findById(untouchedId).orElseThrow().getQuantity()).isEqualByComparingTo("10");
        assertThat(balances.findById(untouchedId).orElseThrow().getTotalValue()).isEqualByComparingTo("50");
        var repeated = issues.approve(77, request); em.flush(); em.clear();
        assertThat(repeated.alreadyPosted()).isTrue();
        assertThat(repeated.movementDocumentId()).isEqualTo(posted.movementDocumentId());
        assertThat(balances.findById(balanceId).orElseThrow().getQuantity()).isEqualByComparingTo("8");
    }

    @Test void clearInboundEventPostsOneIssueAndReducesOneBatchOnce() {
        TenantContext.setTenant("default"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        var warehouse = persist(new Warehouse(), "Auto issue warehouse");
        var unit = new UnitOfMeasure(); unit.setMeasurementNameShort("kg"); persist(unit, "Kilograms");
        var material = new Material(); material.setUnitOfMeasureId(unit.getId()); material.setUnitOfMeasure(unit);
        persist(material, "Auto issue product"); em.flush();
        var batch = new StockBatch(); batch.setWarehouseId(warehouse.getId()); batch.setMaterialId(material.getId());
        batch.setBatchNumber("AUTO-BATCH"); persist(batch, "Auto batch"); em.flush();
        var receipt = new StockMovement(); receipt.setMaterialId(material.getId()); receipt.setWarehouseId(warehouse.getId());
        receipt.setStockBatchId(batch.getId()); receipt.setMovementType(MovementType.RECEIPT);
        receipt.setQuantity(new BigDecimal("10")); receipt.setUnitCost(new BigDecimal("5")); receipt.setMovementDate(LocalDate.now());
        movements.createAndPost(receipt); em.flush(); em.clear();
        UUID balanceId = balances.findByTenantIdAndCompanyIdAndWarehouseIdAndMaterialIdInAndIsDeletedFalse(
            "default", CompanyContext.currentCompany(), warehouse.getId(), List.of(material.getId())).getFirst().getId();
        UUID run = UUID.randomUUID(), warehouseRecord = UUID.randomUUID(), materialRecord = UUID.randomUUID();
        jdbc.update("insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,expected_chain,chain_sha256,status) values(?,?,?,?,?,?,?,?,?,?)",
            run, "default", CompanyContext.currentCompany(), "f".repeat(64), "org", "{}", 2, "e".repeat(64), "d".repeat(64), "COMPLETED");
        jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,row_sha256,content) values(?,?,?,?,?,?,?,?,?,?)",
            warehouseRecord, "default", CompanyContext.currentCompany(), run, 1, "MASTER", "warehouse", "_reference85", "c".repeat(64), "{}");
        jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,row_sha256,content) values(?,?,?,?,?,?,?,?,?,?)",
            materialRecord, "default", CompanyContext.currentCompany(), run, 2, "MASTER", "material", "_reference55", "b".repeat(64), "{}");
        String rawWarehouseId = "8030001a4bcf769a11df9ed8671308d4";
        UUID warehouse1cId = UUID.fromString("671308d4-9ed8-11df-8030-001a4bcf769a");
        jdbc.update("insert into warehouse_source_master_links(tenant_id,company_id,reference_kind,reference_type,source_id,master_id,source_record_id) values(?,?,?,?,?,?,?)",
            "default", CompanyContext.currentCompany(), "WAREHOUSE", "00000055", rawWarehouseId, warehouse.getId(), warehouseRecord);
        jdbc.update("insert into warehouse_source_master_links(tenant_id,company_id,reference_kind,reference_type,source_id,master_id,source_record_id) values(?,?,?,?,?,?,?)",
            "default", CompanyContext.currentCompany(), "MATERIAL", "00000037", "9030001a4bcf769a11df9ed8671308d4", material.getId(), materialRecord);
        jdbc.update("insert into warehouse_source_profiles(id,tenant_id,company_id,category,source_table,source_id,target_id,code,name,search_text,fields) values(?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))",
            materialRecord, "default", CompanyContext.currentCompany(), "MATERIAL_DETAILS", "_reference55",
            "9030001a4bcf769a11df9ed8671308d4", material.getId(), "3203           ", "Auto product", "auto product", "{\"marked\":false,\"isGroup\":false}");
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
            insert into kimyosanoat_event_inbox(event_id,tenant_id,company_id,transfer_id,warehouse_1c_id,event_type,occurred_at,payload,payload_sha256)
            values(?, 'default', ?, 88, ?, 'EXIT_ACCEPTED', current_timestamp, '{}'::jsonb, ?)
            """, eventId, CompanyContext.currentCompany(), warehouse1cId, "a".repeat(64));
        var source = json.createObjectNode().put("id", 88).put("fromWarehouseId", 12).put("productId", 9)
            .put("productNCode", "3203").put("lastStatus", "EXIT_ACCEPTED")
            .put("confirmedNettoKg", 2).put("measureUnit", "kg");
        source.putArray("confirmedStatuses").add("EXIT_ACCEPTED");
        var posted = issues.autoApprove(eventId, 88, warehouse1cId, source, List.of(12L)); em.flush(); em.clear();
        assertThat(posted.alreadyPosted()).isFalse();
        assertThat(balances.findById(balanceId).orElseThrow().getQuantity()).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("select processing_state from kimyosanoat_event_inbox where event_id=?", String.class, eventId))
            .isEqualTo("POSTED");
        var repeated = issues.autoApprove(eventId, 88, warehouse1cId, source, List.of(12L)); em.flush(); em.clear();
        assertThat(repeated.alreadyPosted()).isTrue();
        assertThat(repeated.movementDocumentId()).isEqualTo(posted.movementDocumentId());
        assertThat(balances.findById(balanceId).orElseThrow().getQuantity()).isEqualByComparingTo("8");
    }
}

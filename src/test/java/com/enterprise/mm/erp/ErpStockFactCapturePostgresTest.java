package com.enterprise.mm.erp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ErpStockFactCapturePostgresTest {
    @Test @EnabledIfEnvironmentVariable(named="ERP_TEST_POSTGRES_URL", matches=".+")
    void capturesPostedEvidenceOnceAndRejectsChangedLedger() throws Exception {
        var ds=new SingleConnectionDataSource(System.getenv("ERP_TEST_POSTGRES_URL"),"mm_local","",true);
        var jdbc=new JdbcTemplate(ds); var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var mapper=new ObjectMapper(); var service=new ErpExchangeService(jdbc,mapper,mock(MaterialRepository.class),mock(MaterialGroupRepository.class));
        String tenant="fact-test-"+UUID.randomUUID(); UUID company=UUID.randomUUID(), document=UUID.randomUUID(), movement=UUID.randomUUID(), material=UUID.randomUUID(), warehouse=UUID.randomUUID();
        try {
            jdbc.execute("create temporary table erp_exchange_connection (id uuid, tenant_id text, company_id uuid, enabled boolean)");
            jdbc.execute("create temporary table erp_exchange_posting_config (tenant_id text, company_id uuid, arrival_mode text)");
            jdbc.execute("create temporary table erp_exchange_envelope (id uuid primary key,tenant_id text,company_id uuid,document_kind text,document_id uuid,document_version bigint,payload text,delivery_state text,operation text,posting_date date,created_at timestamp)");
            jdbc.execute("create unique index on erp_exchange_envelope (tenant_id,company_id,document_kind,document_id,document_version,operation) where operation is not null");
            jdbc.execute("create temporary table stock_movement_documents (id uuid,tenant_id text,company_id uuid,status text,version bigint,movement_type text,movement_date date,warehouse_id uuid,posted_at timestamp,reference_type text,is_delete boolean)");
            jdbc.execute("create temporary table stock_movements (id uuid,document_id uuid,tenant_id text,company_id uuid,material_id uuid,warehouse_id uuid,movement_type text,movement_date date,quantity numeric,total_cost numeric,status text,posted_at timestamp,is_delete boolean,reference_type text,reference_id uuid)");
            jdbc.execute("create temporary table materials (id uuid,tenant_id text,company_id uuid,material_type text,is_delete boolean)");
            jdbc.execute("create temporary table stock_movement_allocations (movement_id uuid,tenant_id text,company_id uuid,material_id uuid,on_hand_delta numeric,total_value_delta numeric)");
            jdbc.update("insert into erp_exchange_connection values (?,?,?,true)",UUID.randomUUID(),tenant,company);
            jdbc.update("insert into stock_movement_documents values (?,?,?,'POSTED',1,'RECEIPT',?,?,current_timestamp,'MANUAL_WAREHOUSE_FACT',false)",document,tenant,company,LocalDate.of(2025,3,29),warehouse);
            jdbc.update("insert into materials values (?,?,?,'TMB',false)",material,tenant,company);
            jdbc.update("insert into stock_movements values (?,?,?,?,?,?,'RECEIPT',?,?,?,'POSTED',current_timestamp,false,'MANUAL_WAREHOUSE_FACT',?)",movement,document,tenant,company,material,warehouse,LocalDate.of(2025,3,29),new BigDecimal("100.0000000"),new BigDecimal("2500000.0000"),document);
            jdbc.update("insert into stock_movement_allocations values (?,?,?,?,?,?)",movement,tenant,company,material,new BigDecimal("100"),new BigDecimal("2500000"));
            TenantContext.setTenant(tenant); CompanyContext.setCompany(company);
            tx.executeWithoutResult(status -> {service.captureStockFact(document); status.setRollbackOnly();});
            assertThat(jdbc.queryForObject("select count(*) from erp_exchange_envelope",Integer.class)).isZero();
            var first=tx.execute(status -> service.captureStockFact(document));
            var second=tx.execute(status -> service.captureStockFact(document));
            assertThat(first.get("created")).isEqualTo(true); assertThat(second.get("created")).isEqualTo(false);
            assertThat(second.get("envelopeId")).isEqualTo(first.get("envelopeId"));
            var event=mapper.readTree(jdbc.queryForObject("select payload from erp_exchange_envelope",String.class));
            assertThat(event.path("postingDate").asText()).isEqualTo("2025-03-29");
            assertThat(new BigDecimal(event.path("lines").get(0).path("amount").asText())).isEqualByComparingTo("2500000");
            jdbc.update("update stock_movement_allocations set total_value_delta=1 where movement_id=?",movement);
            assertThatThrownBy(() -> tx.execute(status -> service.captureStockFact(document))).isInstanceOf(ConflictException.class);
            CompanyContext.setCompany(UUID.randomUUID());
            assertThatThrownBy(() -> tx.execute(status -> service.captureStockFact(document))).isInstanceOf(ConflictException.class);
        } finally { TenantContext.clear(); CompanyContext.clear(); ds.destroy(); }
    }
}

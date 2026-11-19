package com.enterprise.mm.erp;
import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class TransferAllocationScalePostgresTest {
    @Test @EnabledIfEnvironmentVariable(named="ERP_TEST_POSTGRES_URL", matches=".+")
    void migratedConstraintAllowsSevenAndRejectsInvalidScalesAndZeroQuantity() {
        var ds=new SingleConnectionDataSource(System.getenv("ERP_TEST_POSTGRES_URL"),"mm_local","",true);
        try {
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("create temporary table test_transfer_scale (like public.transfer_shipment_allocations including defaults including constraints)");
            String insert="""
                insert into test_transfer_scale
                (id,tenant_id,company_id,created_at,version,name,shipment_id,transfer_document_id,transfer_line_id,material_id,
                 unit_code,quantity_scale,source_warehouse_id,dispatched_quantity,original_value,book_value,currency_code,book_currency_code,exchange_rate)
                values (?,?,?,current_timestamp,0,'test',?,?,?,?,?,?,?,?,0,0,'UZS','UZS',1)
                """;
            UUID company=UUID.randomUUID(), reference=UUID.randomUUID();
            java.util.function.BiConsumer<Integer,java.math.BigDecimal> add=(scale,quantity) -> jdbc.update(insert,UUID.randomUUID(),"test",company,reference,reference,reference,reference,"kg",scale,reference,quantity);
            add.accept(7,new java.math.BigDecimal("0.0000001"));
            assertThat(jdbc.queryForObject("select dispatched_quantity from test_transfer_scale",java.math.BigDecimal.class)).isEqualByComparingTo("0.0000001");
            assertThatThrownBy(() -> add.accept(8,java.math.BigDecimal.ONE)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> add.accept(-1,java.math.BigDecimal.ONE)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> add.accept(7,java.math.BigDecimal.ZERO)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally { ds.destroy(); }
    }
}

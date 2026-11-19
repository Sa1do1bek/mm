package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.StockRiskSeverity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.jdbc.Sql;

@Sql(statements = """
    CREATE TABLE IF NOT EXISTS warehouse_imported_movements (
      id uuid PRIMARY KEY, tenant_id varchar(80), company_id uuid,
      material_id uuid, warehouse_id uuid, movement_date date,
      movement_type varchar(30), quantity decimal(22,7));
    """, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class StockRiskAlertHistoryIntegrationTest extends StockRiskAlertSqlIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void historicalIssuesCombineWithOperationalIssuesWithoutChangingBalances() {
        var before = runHistory();
        var high = before.getContent().stream().filter(r -> r.materialCode().equals("B-HIGH")).findFirst().orElseThrow();
        insertHistory(high.materialId(), high.warehouseId(), TenantContext.currentTenant(), CompanyContext.currentCompany(), "ISSUE", "-90", 1);
        insertHistory(high.materialId(), high.warehouseId(), TenantContext.currentTenant(), CompanyContext.currentCompany(), "RECEIPT", "90", 1);
        insertHistory(high.materialId(), high.warehouseId(), TenantContext.currentTenant(), CompanyContext.currentCompany(), "TRANSFER", "-90", 1);
        insertHistory(high.materialId(), high.warehouseId(), TenantContext.currentTenant(), CompanyContext.currentCompany(), "WRITE_OFF", "-90", 1);
        insertHistory(high.materialId(), high.warehouseId(), TenantContext.currentTenant(), CompanyContext.currentCompany(), "ISSUE", "-90", 120);
        insertHistory(high.materialId(), UUID.randomUUID(), TenantContext.currentTenant(), CompanyContext.currentCompany(), "ISSUE", "-90", 1);
        insertHistory(high.materialId(), high.warehouseId(), "another-tenant", CompanyContext.currentCompany(), "ISSUE", "-90", 1);
        insertHistory(high.materialId(), high.warehouseId(), TenantContext.currentTenant(), UUID.randomUUID(), "ISSUE", "-90", 1);
        var after = runHistory().getContent().stream().filter(r -> r.materialId().equals(high.materialId())).findFirst().orElseThrow();
        assertThat(after.consumptionQuantity()).isEqualByComparingTo("990");
        assertThat(after.averageDailyUsage()).isEqualByComparingTo("11");
        assertThat(after.availableQuantity()).isEqualByComparingTo(high.availableQuantity());
        assertThat(after.severity()).isEqualTo(StockRiskSeverity.HIGH);
        assertThat(jdbc.queryForObject("select sum(available_quantity) from stock_balances where material_id=?", BigDecimal.class, high.materialId()))
                .isEqualByComparingTo("50");
    }

    @Test
    void historicalOnlyUsageProducesForecastAndRespectsSelectedPeriod() {
        var material = runHistory().getContent().stream().filter(r -> r.materialCode().equals("E-OK")).findFirst().orElseThrow();
        insertHistory(material.materialId(), material.warehouseId(), TenantContext.currentTenant(), CompanyContext.currentCompany(), "ISSUE", "-900", 60);
        var ninety = runHistory().getContent().stream().filter(r -> r.materialId().equals(material.materialId())).findFirst().orElseThrow();
        assertThat(ninety.consumptionQuantity()).isEqualByComparingTo("900");
        assertThat(ninety.daysUntilOutOfStock()).isEqualTo(100);
        var thirty = service.search(30, true, material.materialId(), null, null, null, null, PageRequest.of(0,20));
        assertThat(thirty.getContent()).singleElement().satisfies(row -> {
            assertThat(row.consumptionQuantity()).isEqualByComparingTo("0");
            assertThat(row.daysUntilOutOfStock()).isNull();
        });
    }

    @Test
    void historyOnlyMaterialIsScopedAndRetainedAsDepletedStock() {
        var reference = runHistory().getContent().getFirst();
        var material = new com.enterprise.mm.entity.Material();
        material.setCode("HISTORY-ONLY");
        material.setName("History only");
        material.setStatus("ACTIVE");
        material.setUnitOfMeasureId(reference.unitOfMeasureId());
        material = materials.saveAndFlush(material);
        UUID id = material.getId();
        insertHistory(id, reference.warehouseId(), "another-tenant", CompanyContext.currentCompany(), "ISSUE", "-90", 1);
        assertThat(runHistory().getContent()).noneMatch(row -> row.materialId().equals(id));
        insertHistory(id, reference.warehouseId(), TenantContext.currentTenant(), CompanyContext.currentCompany(), "ISSUE", "-90", 1);
        assertThat(runHistory().getContent()).anySatisfy(row -> {
            assertThat(row.materialId()).isEqualTo(id);
            assertThat(row.availableQuantity()).isEqualByComparingTo("0");
            assertThat(row.consumptionQuantity()).isEqualByComparingTo("90");
            assertThat(row.severity()).isEqualTo(StockRiskSeverity.CRITICAL);
        });
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThat(runHistory().getContent()).anySatisfy(row -> assertThat(row.materialId()).isEqualTo(id));
    }

    private org.springframework.data.domain.Page<com.enterprise.mm.payload.StockRiskAlertResponse> runHistory() {
        return service.search(90, true, null, null, null, null, null, PageRequest.of(0,50));
    }

    private void insertHistory(UUID material, UUID warehouse, String tenant, UUID company, String type, String quantity, int days) {
        jdbc.update("insert into warehouse_imported_movements values (?,?,?,?,?,?,?,?)", UUID.randomUUID(),tenant,company,
                material,warehouse,LocalDate.now().minusDays(days),type,new BigDecimal(quantity));
    }
}

package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.payload.DamagedStockDtos;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"app.seed.enabled=false", "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false"})
@ActiveProfiles("test")
@EntityScan(basePackages = {"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity", "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
class DamagedStockIntegrationTest {
    @Autowired DamagedStockService damage;
    @Autowired DamagedStockCaseRepository cases;
    @Autowired StockMovementService stock;
    @Autowired CompanyRepository companies;
    @Autowired WarehouseRepository warehouses;
    @Autowired MaterialRepository materials;
    @Autowired UnitOfMeasureRepository units;
    @Autowired JdbcTemplate jdbc;
    UUID company, warehouse, materialId, balanceId;

    @BeforeEach void setup() {
        TenantContext.setTenant("damage-" + UUID.randomUUID().toString().substring(0, 8));
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        var c = new Company(); c.setName("Damage test"); c.setStatus("ACTIVE");
        company = companies.saveAndFlush(c).getId(); CompanyContext.setCompany(company);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("damage-manager", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var w = new Warehouse(); w.setCode("WH"); w.setName("Warehouse"); w.setStatus("ACTIVE"); warehouse = warehouses.saveAndFlush(w).getId();
        var u = new UnitOfMeasure(); u.setCode("EA"); u.setName("Pieces"); u.setStatus("ACTIVE"); u.setUnitType(MeasurementUnitType.QUANTITY); u.setStockQuantityScale(0); u = units.saveAndFlush(u);
        var m = new Material(); m.setCode("MAT"); m.setName("Material"); m.setStatus("ACTIVE"); m.setMaterialType(MaterialType.TMB); m.setUnitOfMeasureId(u.getId()); materialId = materials.saveAndFlush(m).getId();
        var receipt = new StockMovement(); receipt.setMaterialId(materialId); receipt.setWarehouseId(warehouse); receipt.setMovementType(MovementType.RECEIPT);
        receipt.setMovementDate(LocalDate.now()); receipt.setQuantity(new BigDecimal("10")); receipt.setUnitCost(new BigDecimal("10"));
        stock.createAndPost(receipt);
        balanceId = jdbc.queryForObject("select id from stock_balances where company_id=? and material_id=? and warehouse_id=? and is_delete=false", UUID.class, company, materialId, warehouse);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear(); }

    @Test void quarantineReleaseAndRetriesPreserveStock() {
        var request = new DamagedStockDtos.Report(balanceId, DamagedStockCase.Condition.DAMAGED, new BigDecimal("2"), "Damaged packaging");
        var reported = damage.report(request, "same-report");
        assertThat(damage.report(request, "same-report").id()).isEqualTo(reported.id());
        assertBalance("10", "8", "2", "100");
        var released = damage.release(reported.id(), new DamagedStockDtos.Release("Inspected and safe"));
        assertThat(released.status()).isEqualTo(DamagedStockCase.Status.RELEASED);
        damage.release(reported.id(), new DamagedStockDtos.Release("Inspected and safe"));
        assertBalance("10", "10", "0", "100");
    }

    @Test void approvedWriteOffConsumesQuarantinedStockAndPostsOnlyOnce() {
        var reported = damage.report(new DamagedStockDtos.Report(balanceId, DamagedStockCase.Condition.DAMAGED, new BigDecimal("2"), "Broken"), "write-off");
        var stored = cases.findById(reported.id()).orElseThrow(); stored.setStatus("APPROVED"); cases.saveAndFlush(stored);
        var result = damage.post(reported.id(), new DamagedStockDtos.Post(LocalDate.now()));
        assertThat(result.status()).isEqualTo(DamagedStockCase.Status.WRITTEN_OFF);
        assertThat(damage.post(reported.id(), new DamagedStockDtos.Post(LocalDate.now())).movementId()).isEqualTo(result.movementId());
        assertBalance("8", "8", "0", "80");
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where reference_type='DAMAGED_STOCK' and reference_id=?", Long.class, reported.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quarantine_delta from stock_movement_allocations where movement_id=?", BigDecimal.class, result.movementId())).isEqualByComparingTo("-2");
        assertThatThrownBy(() -> stock.cancelPosted(result.movementId())).hasMessageContaining("Damaged stock");
        assertBalance("8", "8", "0", "80");
    }

    @Test void rejectsUnapprovedWriteOffAndInsufficientStockWithoutChangingTheLedger() {
        assertThatThrownBy(() -> damage.report(new DamagedStockDtos.Report(balanceId, DamagedStockCase.Condition.DAMAGED,
            new BigDecimal("11"), "Broken"), "too-much")).isInstanceOf(RuntimeException.class);
        assertBalance("10", "10", "0", "100");
        var reported = damage.report(new DamagedStockDtos.Report(balanceId, DamagedStockCase.Condition.DAMAGED,
            new BigDecimal("2"), "Broken"), "unapproved");
        assertThatThrownBy(() -> damage.post(reported.id(), new DamagedStockDtos.Post(LocalDate.now()))).isInstanceOf(RuntimeException.class);
        assertBalance("10", "8", "2", "100");
    }

    @Test void postingRequiresFinanceAuthority() {
        var reported = damage.report(new DamagedStockDtos.Report(balanceId, DamagedStockCase.Condition.DAMAGED,
            new BigDecimal("2"), "Broken"), "finance-only");
        var stored = cases.findById(reported.id()).orElseThrow(); stored.setStatus("APPROVED"); cases.saveAndFlush(stored);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("keeper", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"))));
        assertThatThrownBy(() -> damage.post(reported.id(), new DamagedStockDtos.Post(LocalDate.now())))
            .hasMessageContaining("Finance");
        assertBalance("10", "8", "2", "100");
    }

    @Test void simultaneousWriteOffRequestsProduceOneMovement() throws Exception {
        var reported = damage.report(new DamagedStockDtos.Report(balanceId, DamagedStockCase.Condition.DAMAGED,
            new BigDecimal("2"), "Broken"), "concurrent");
        var stored = cases.findById(reported.id()).orElseThrow(); stored.setStatus("APPROVED"); cases.saveAndFlush(stored);
        String tenant = TenantContext.currentTenant();
        var gate = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<UUID> call = () -> {
                TenantContext.setTenant(tenant); CompanyContext.setCompany(company);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("damage-manager", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                try { gate.await(); return damage.post(reported.id(), new DamagedStockDtos.Post(LocalDate.now())).movementId(); }
                finally { SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear(); }
            };
            var first = executor.submit(call); var second = executor.submit(call); gate.countDown();
            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertBalance("8", "8", "0", "80");
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where reference_type='DAMAGED_STOCK' and reference_id=?", Long.class, reported.id())).isEqualTo(1);
    }
    private void assertBalance(String quantity, String available, String quarantine, String value) {
        var row = jdbc.queryForMap("select quantity,available_quantity,quarantine_quantity,total_value from stock_balances where id=?", balanceId);
        assertThat((BigDecimal) row.get("quantity")).isEqualByComparingTo(quantity);
        assertThat((BigDecimal) row.get("available_quantity")).isEqualByComparingTo(available);
        assertThat((BigDecimal) row.get("quarantine_quantity")).isEqualByComparingTo(quarantine);
        assertThat((BigDecimal) row.get("total_value")).isEqualByComparingTo(value);
    }
}

package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.firebase.enabled=false",
    "mm.integrations.cbu.enabled=false"
})
@ActiveProfiles("test")
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers
@Transactional
class StockMovementSearchPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    @Autowired StockMovementRepository movements;
    @Autowired MaterialRepository materials;
    @Autowired WarehouseRepository warehouses;
    @Autowired com.enterprise.mm.repository.TransferDocumentRepository transfers;
    @Autowired com.enterprise.mm.service.StockMovementService service;
    UUID company, warehouse;
    LocalDate date = LocalDate.of(2026, 10, 22);
    @BeforeEach void setup() {
        TenantContext.setTenant("movement-search-test");
        company = UUID.randomUUID(); CompanyContext.setCompany(company);
        var w = new Warehouse(); w.setName("Main warehouse"); warehouse = warehouses.saveAndFlush(w).getId();
        var m = new Material(); m.setCode("BEARING-6204"); m.setName("Pump bearing"); m = materials.saveAndFlush(m);
        var movement = new StockMovement(); movement.setCode("RECEIPT-ONE"); movement.setName("Receipt for pump");
        movement.setMaterialId(m.getId()); movement.setWarehouseId(warehouse);
        movement.setMovementType(MovementType.RECEIPT); movement.setQuantity(BigDecimal.TEN); movement.setMovementDate(date);
        movements.saveAndFlush(movement);
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }
    @Test void legacySearchWithAllOptionalFiltersAbsentWorksOnPostgres() {
        assertThat(movements.search(TenantContext.currentTenant(), company, false, null, null, null,
            List.of(), true, warehouse, List.of(), true, null, null, null, null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }
    @Test void datesAndCaseInsensitiveMaterialFiltersKeepTheirMeaning() {
        assertThat(search(date, date, date, "RECEIPT-ONE", "bearing", "PUMP")).isEqualTo(1);
        assertThat(search(date.plusDays(1), null, null, null, null, null)).isZero();
        assertThat(search(null, date.minusDays(1), null, null, null, null)).isZero();
        assertThat(search(null, null, date.plusDays(1), null, null, null)).isZero();
        assertThat(search(null, null, null, "not-found", null, null)).isZero();
        assertThat(search(null, null, null, null, "not-found", null)).isZero();
        assertThat(search(null, null, null, null, null, "not-found")).isZero();
    }
    @Test void shipmentMovementsShowTheActualWarehouseRouteWithoutChangingQuantities() {
        var destination = new Warehouse(); destination.setName("Workshop warehouse");
        destination = warehouses.saveAndFlush(destination);
        var transfer = new com.enterprise.mm.entity.TransferDocument(); transfer.setName("Bearing delivery");
        transfer.setSourceWarehouseId(warehouse); transfer.setDestinationWarehouseId(destination.getId());
        transfer = transfers.saveAndFlush(transfer);
        for (String reference : List.of("TRANSFER_DISPATCH", "TRANSFER_GOOD_RECEIPT", "TRANSFER_QUARANTINE_RECEIPT")) {
            var movement = new com.enterprise.mm.payload.StockMovementResponse();
            movement.setReferenceType(reference); movement.setReferenceId(transfer.getId());
            movement.setMovementType(reference.equals("TRANSFER_DISPATCH") ? MovementType.TRANSFER : MovementType.RECEIPT);
            movement.setQuantity(BigDecimal.TEN);
            service.enrichMovements(List.of(movement));
            assertThat(movement.getSourceWarehouseId()).isEqualTo(warehouse);
            assertThat(movement.getDestinationWarehouseId()).isEqualTo(destination.getId());
            assertThat(movement.getSourceLocation()).contains("Main warehouse");
            assertThat(movement.getDestinationLocation()).contains("Workshop warehouse");
            assertThat(movement.getCounterparty()).contains(reference.equals("TRANSFER_DISPATCH") ? "Workshop warehouse" : "Main warehouse");
            assertThat(movement.getQuantity()).isEqualByComparingTo(BigDecimal.TEN);
        }
    }
    private long search(LocalDate from, LocalDate to, LocalDate day, String query, String code, String name) {
        return movements.search(TenantContext.currentTenant(), company, false, query,
            from == null ? null : from.atStartOfDay(), to == null ? null : to.atTime(23, 59, 59),
            List.of(), true, warehouse, List.of(), true, null, null, null, null, day, List.of(), true,
            null, code, name, List.of(), true, PageRequest.of(0, 10)).getTotalElements();
    }
}

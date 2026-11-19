package com.enterprise.mm.service.transfer;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class TransferShipmentPersistenceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    @Autowired TransferShipmentRepository shipments;
    @Autowired TransferShipmentAllocationRepository allocations;
    @Autowired TransferAllocationClearanceRepository clearances;
    @Autowired TransferDocumentRepository transfers;
    @Autowired TransferDocumentLineRepository lines;
    @Autowired WarehouseRepository warehouses;
    @Autowired MaterialRepository materials;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void scope() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }
    @AfterEach void clearScope() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void persistsExactValuesAndRejectsDuplicateOperations() {
        var fixture = fixture();
        var shipment = shipments.saveAndFlush(shipment(fixture.transfer(), "unique-" + UUID.randomUUID()));
        var allocation = allocations.saveAndFlush(allocation(fixture, shipment));
        var clearance = clearance(allocation, "receive-" + UUID.randomUUID());
        clearances.saveAndFlush(clearance);
        var stored = allocations.findById(allocation.getId()).orElseThrow();
        assertThat(stored.getBookValue()).isEqualByComparingTo("0.0100");
        assertThat(stored.getBatchNumber()).isEqualTo("LOT-2026");
        assertThat(stored.getExpiryDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(stored.getExchangeRate()).isEqualByComparingTo("1.000000");
        assertThatThrownBy(() -> shipments.saveAndFlush(shipment(fixture.transfer(), shipment.getOperationKey())))
            .hasStackTraceContaining("uq_transfer_shipment_operation");
        assertThatThrownBy(() -> clearances.saveAndFlush(clearance(allocation, clearance.getOperationKey())))
            .hasStackTraceContaining("uq_transfer_clearance_operation");
    }
    @Test void rolledBackDispatchLeavesNeitherShipmentNorAllocation() {
        var fixture = fixture();
        UUID[] ids = new UUID[2];
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var shipment = shipments.saveAndFlush(shipment(fixture.transfer(), "rollback-" + UUID.randomUUID()));
            var allocation = allocations.saveAndFlush(allocation(fixture, shipment));
            ids[0] = shipment.getId(); ids[1] = allocation.getId();
            tx.setRollbackOnly();
        });
        assertThat(shipments.findById(ids[0])).isEmpty();
        assertThat(allocations.findById(ids[1])).isEmpty();
    }
    @Test void rejectsCrossScopeAndCrossTransferLinks() {
        var fixture = fixture();
        var shipment = shipments.saveAndFlush(shipment(fixture.transfer(), "scope-" + UUID.randomUUID()));
        var foreign = allocation(fixture, shipment);
        foreign.setTenantId("another-tenant");
        assertThatThrownBy(() -> allocations.saveAndFlush(foreign)).hasStackTraceContaining("fk_transfer_allocation_");
        var another = fixture();
        var wrongLine = allocation(another, shipment);
        assertThatThrownBy(() -> allocations.saveAndFlush(wrongLine)).hasStackTraceContaining("fk_transfer_allocation_");
    }
    @Test void postedFactsCannotBeRewrittenOrDeletedWithSql() {
        var fixture = fixture();
        var shipment = shipments.saveAndFlush(shipment(fixture.transfer(), "immutable-" + UUID.randomUUID()));
        var allocation = allocations.saveAndFlush(allocation(fixture, shipment));
        assertThatThrownBy(() -> jdbc.update("update transfer_shipment_allocations set dispatched_quantity=9 where id=?", allocation.getId()))
            .hasStackTraceContaining("Transfer facts are append-only");
        assertThatThrownBy(() -> jdbc.update("delete from transfer_shipments where id=?", shipment.getId()))
            .hasStackTraceContaining("Transfer facts are append-only");
    }
    @Test void rejectsNonpositiveAllocationQuantity() {
        var fixture = fixture();
        var shipment = shipments.saveAndFlush(shipment(fixture.transfer(), "quantity-" + UUID.randomUUID()));
        var allocation = allocation(fixture, shipment);
        allocation.setDispatchedQuantity(BigDecimal.ZERO);
        assertThatThrownBy(() -> allocations.saveAndFlush(allocation)).hasStackTraceContaining("ck_transfer_allocation_quantity");
    }
    private Fixture fixture() {
        var source = new Warehouse(); source.setName("Transfer source"); source = warehouses.saveAndFlush(source);
        var destination = new Warehouse(); destination.setName("Transfer destination"); destination = warehouses.saveAndFlush(destination);
        var material = new Material(); material.setName("Transfer material"); material = materials.saveAndFlush(material);
        var transfer = new TransferDocument(); transfer.setName("Shipment test");
        transfer.setSourceWarehouseId(source.getId()); transfer.setDestinationWarehouseId(destination.getId());
        transfer = transfers.saveAndFlush(transfer);
        var line = new TransferDocumentLine(); line.setName("Shipment line"); line.setTransferDocumentId(transfer.getId());
        line.setMaterialId(material.getId()); line.setPlannedQuantity(new BigDecimal("3")); line = lines.saveAndFlush(line);
        return new Fixture(transfer, line);
    }
    private TransferShipment shipment(TransferDocument transfer, String key) {
        var shipment = new TransferShipment(); shipment.setName("Dispatch"); shipment.setTransferDocumentId(transfer.getId());
        shipment.setDispatchedDate(LocalDate.now()); shipment.setOperationKey(key); return shipment;
    }
    private TransferShipmentAllocation allocation(Fixture fixture, TransferShipment shipment) {
        var allocation = new TransferShipmentAllocation(); allocation.setName("Dispatch allocation");
        allocation.setShipmentId(shipment.getId()); allocation.setTransferDocumentId(fixture.transfer().getId());
        allocation.setTransferLineId(fixture.line().getId()); allocation.setMaterialId(fixture.line().getMaterialId());
        allocation.setSourceWarehouseId(fixture.transfer().getSourceWarehouseId());
        allocation.setDispatchedQuantity(new BigDecimal("3")); allocation.setBookValue(new BigDecimal("0.01"));
        allocation.setOriginalValue(new BigDecimal("0.01")); allocation.setCurrencyCode("UZS");
        allocation.setBookCurrencyCode("UZS"); allocation.setExchangeRate(BigDecimal.ONE);
        allocation.setUnitCode("pcs"); allocation.setQuantityScale(4); allocation.setBatchNumber("LOT-2026");
        allocation.setExpiryDate(LocalDate.of(2027, 1, 1)); return allocation;
    }
    private TransferAllocationClearance clearance(TransferShipmentAllocation allocation, String key) {
        var clearance = new TransferAllocationClearance(); clearance.setName("Receipt");
        clearance.setAllocationId(allocation.getId()); clearance.setKind(TransferAllocationClearance.Kind.GOOD_RECEIPT);
        clearance.setQuantity(BigDecimal.ONE); clearance.setBookValue(new BigDecimal("0.0033"));
        clearance.setOperationKey(key); clearance.setPostingDate(LocalDate.now()); return clearance;
    }
    private record Fixture(TransferDocument transfer, TransferDocumentLine line) {}
}

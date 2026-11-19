package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.payload.WarehouseLayoutStockDtos.Precision;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@EntityScan(basePackages = {"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class WarehouseLayoutStockPostgresTest extends StockExpiryIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Test void completeAddressCountsPreserveUnknownPrecisionAndExcludeOtherScopes() {
        UUID[] references = tx.execute(status -> {
            var location = new StorageLocation(); location.setWarehouseId(warehouse); persistLocal(location);
            var aggregate = new StorageLocation(); aggregate.setWarehouseId(warehouse);
            aggregate.setDescription("WarehouseAggregate:source-warehouse\nNo physical bin is known."); persistLocal(aggregate);
            var bin = new StorageBin(); bin.setWarehouseId(warehouse); bin.setStorageLocationId(location.getId());
            bin.setBinCode("A-01-01"); persistLocal(bin);
            var exact = em.find(StockBalance.class, ids.get("yesterday")); exact.setStorageLocationId(location.getId()); exact.setStorageBinId(bin.getId());
            em.find(StockBalance.class, ids.get("today")).setStorageLocationId(location.getId());
            em.find(StockBalance.class, ids.get("tomorrow")).setStorageLocationId(aggregate.getId());
            addBalance("foreign-tenant", UUID.randomUUID(), company, warehouse, false);
            var otherCompany = persistLocal(new Company()); otherCompany.setCompanyId(otherCompany.getId());
            addBalance(tenant, UUID.randomUUID(), otherCompany.getId(), warehouse, false);
            addBalance(tenant, UUID.randomUUID(), company, otherWarehouse, false);
            addBalance(tenant, UUID.randomUUID(), company, warehouse, true);
            return new UUID[] { location.getId(), aggregate.getId(), bin.getId() };
        });
        var result = balances.layoutLocations(warehouse, null);
        assertThat(result.locations()).extracting(row -> row.positionCount()).containsExactlyInAnyOrder(1L, 1L, 1L, 8L);
        assertThat(result.locations()).filteredOn(row -> references[2].equals(row.storageBinId())).singleElement()
            .satisfies(row -> { assertThat(row.precision()).isEqualTo(Precision.BIN); assertThat(row.positionCount()).isEqualTo(1); });
        assertThat(result.locations()).filteredOn(row -> references[0].equals(row.storageLocationId()) && row.storageBinId() == null).singleElement()
            .satisfies(row -> assertThat(row.precision()).isEqualTo(Precision.LOCATION));
        assertThat(result.locations()).filteredOn(row -> references[1].equals(row.storageLocationId())).singleElement()
            .satisfies(row -> assertThat(row.precision()).isEqualTo(Precision.WAREHOUSE));
        assertThat(result.locations()).filteredOn(row -> row.storageLocationId() == null).singleElement()
            .satisfies(row -> assertThat(row.precision()).isEqualTo(Precision.WAREHOUSE));
    }

    @Test void searchSharesStockListPredicatesAndNeverCombinesQuantities() {
        assertThat(balances.layoutLocations(warehouse, "today").locations()).singleElement()
            .satisfies(row -> assertThat(row.positionCount()).isEqualTo(1));
        assertThat(balances.layoutLocations(warehouse, "no-such-material").locations()).isEmpty();
        assertThatThrownBy(() -> balances.layoutLocations(otherWarehouse, null))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void httpWarehouseStockReadIsCompleteAndReadOnly() throws Exception {
        long before = tx.execute(status -> em.createQuery("select count(b) from StockBalance b", Long.class).getSingleResult());
        mvc.perform(get("/api/warehouses/" + warehouse + "/layout/stock").with(user("layout-reader").roles("VIEWER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.locations.length()").value(1))
            .andExpect(jsonPath("$.data.locations[0].positionCount").value(11))
            .andExpect(jsonPath("$.data.locations[0].precision").value("WAREHOUSE"));
        long after = tx.execute(status -> em.createQuery("select count(b) from StockBalance b", Long.class).getSingleResult());
        assertThat(after).isEqualTo(before);
    }

    @Test void httpLayoutSavePersistsRejectsStaleVersionsAndProtectsReadOnlyRoles() throws Exception {
        String endpoint = "/api/warehouses/" + warehouse + "/layout";
        String draft = "{\"version\":null,\"layout\":{\"width\":40,\"depth\":24,\"height\":8,\"items\":[]}}";
        mvc.perform(get(endpoint).with(user("reader").roles("VIEWER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").doesNotExist());
        mvc.perform(put(endpoint).with(user("reader").roles("VIEWER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString())
            .contentType("application/json").content(draft)).andExpect(status().isForbidden());
        mvc.perform(put(endpoint).with(user("manager").roles("MM_MANAGER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString())
            .contentType("application/json").content(draft))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(put(endpoint).with(user("manager").roles("MM_MANAGER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString())
            .contentType("application/json").content(draft)).andExpect(status().isConflict());
        mvc.perform(get(endpoint).with(user("reader").roles("VIEWER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0))
            .andExpect(jsonPath("$.data.layout.width").value(40));
        mvc.perform(put(endpoint).with(user("manager").roles("MM_MANAGER"))
            .header("X-Tenant-Id", tenant).header("X-Company-Id", company.toString())
            .contentType("application/json").content(draft.replace("null", "0").replace("40", "-1")))
            .andExpect(status().isBadRequest());
    }

    private <T extends BusinessEntity> T persistLocal(T entity) {
        entity.setTenantId(tenant); entity.setCompanyId(company); entity.setName("Layout stock fixture");
        entity.setCode("LAYOUT-" + UUID.randomUUID()); entity.setStatus("ACTIVE"); em.persist(entity); return entity;
    }
    private void addBalance(String tenantId, UUID batchId, UUID companyId, UUID warehouseId, boolean deleted) {
        var batch = new StockBatch(); batch.setId(batchId); batch.setMaterialId(material); batch.setWarehouseId(warehouseId);
        // Use a separate generated batch identity to satisfy the existing position key.
        batch.setId(null); persistLocal(batch);
        var balance = new StockBalance(); balance.setMaterialId(material); balance.setStockBatchId(batch.getId());
        balance.setWarehouseId(warehouseId); balance.setQuantity(BigDecimal.TEN); persistLocal(balance);
        balance.setTenantId(tenantId); balance.setCompanyId(companyId);
        if (deleted) balance.softDelete();
    }
}

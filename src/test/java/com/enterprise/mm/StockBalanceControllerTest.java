package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockBalanceControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired com.enterprise.mm.repository.CompanyRepository companies;
    private UUID companyId;

    @org.junit.jupiter.api.BeforeEach
    void companyFixture() {
        com.enterprise.mm.common.TenantContext.setTenant("default");
        var company = new com.enterprise.mm.entity.Company();
        company.setCode("BALANCE-COMPANY-" + UUID.randomUUID());
        company.setName("Balance test company"); company.setStatus("ACTIVE");
        company = companies.saveAndFlush(company);
        company.setCompanyId(company.getId()); company = companies.saveAndFlush(company);
        companyId = company.getId(); com.enterprise.mm.common.CompanyContext.setCompany(companyId);
    }

    @org.junit.jupiter.api.AfterEach
    void clearCompanyFixtureContext() {
        com.enterprise.mm.common.TenantContext.clear(); com.enterprise.mm.common.CompanyContext.clear();
    }
    @Autowired MaterialRepository materialRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StockBatchRepository stockBatchRepository;
    @Autowired StorageLocationRepository storageLocationRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;

    @Test
    void stockBalanceSearchFiltersByWarehouseAndBatch() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Material material = material("MAT-BAL-FILTER-" + suffix);
        Warehouse warehouseA = warehouse("WH-BAL-FILTER-A-" + suffix);
        Warehouse warehouseB = warehouse("WH-BAL-FILTER-B-" + suffix);
        StorageLocation locationA = storageLocation("LOC-BAL-FILTER-A-" + suffix, warehouseA);
        StorageLocation locationB = storageLocation("LOC-BAL-FILTER-B-" + suffix, warehouseA);
        StockBatch expectedBatch = stockBatch("BATCH-BAL-FILTER-EXPECTED-" + suffix, material, warehouseA);
        StockBatch otherBatch = stockBatch("BATCH-BAL-FILTER-OTHER-" + suffix, material, warehouseA);
        StockBatch otherWarehouseBatch = stockBatch("BATCH-BAL-FILTER-WH-" + suffix, material, warehouseB);
        StockBalance expected = stockBalance("BAL-FILTER-EXPECTED-" + suffix, material, warehouseA, locationA, expectedBatch);
        stockBalance("BAL-FILTER-WRONG-BATCH-" + suffix, material, warehouseA, locationB, otherBatch);
        stockBalance("BAL-FILTER-WRONG-WH-" + suffix, material, warehouseB, null, otherWarehouseBatch);

        mockMvc.perform(get("/api/stock/balances")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", companyId.toString())
                .header("Accept-Language", "en")
                .param("warehouseId", warehouseA.getId().toString())
                .param("stockBatchId", expectedBatch.getId().toString())
                .param("storageLocationId", locationA.getId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(expected.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].companyName").isNotEmpty())
            .andExpect(jsonPath("$.data.content[0].materialName").value("Material"))
            .andExpect(jsonPath("$.data.content[0].warehouseId").value(warehouseA.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].warehouseName").value("Warehouse"))
            .andExpect(jsonPath("$.data.content[0].stockBatchId").value(expectedBatch.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].stockBatchName").value("Stock Batch"));
    }

    private Material material(String code) {
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private StorageLocation storageLocation(String code, Warehouse warehouse) {
        StorageLocation location = new StorageLocation();
        location.setCode(code);
        location.setName(code);
        location.setStatus("ACTIVE");
        location.setWarehouseId(warehouse.getId());
        return storageLocationRepository.save(location);
    }

    private StockBatch stockBatch(String code, Material material, Warehouse warehouse) {
        StockBatch batch = new StockBatch();
        batch.setCode(code);
        batch.setName(code);
        batch.setStatus("ACTIVE");
        batch.setMaterialId(material.getId());
        batch.setWarehouseId(warehouse.getId());
        batch.setBatchNumber(code);
        batch.setQuantity(BigDecimal.TEN);
        return stockBatchRepository.save(batch);
    }

    private StockBalance stockBalance(String code, Material material, Warehouse warehouse, StorageLocation storageLocation, StockBatch stockBatch) {
        StockBalance balance = new StockBalance();
        balance.setCode(code);
        balance.setName(code);
        balance.setStatus("ACTIVE");
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setStorageLocationId(storageLocation == null ? null : storageLocation.getId());
        balance.setStockBatchId(stockBatch.getId());
        balance.setQuantity(BigDecimal.TEN);
        balance.setAvailableQuantity(BigDecimal.TEN);
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(BigDecimal.TEN);
        return stockBalanceRepository.save(balance);
    }
}

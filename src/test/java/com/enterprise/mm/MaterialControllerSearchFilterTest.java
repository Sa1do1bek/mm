package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialAttribute;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.repository.MaterialAttributeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
class MaterialControllerSearchFilterTest {
    @Autowired MockMvc mockMvc;
    @Autowired MaterialRepository materialRepository;
    @Autowired MaterialAttributeRepository materialAttributeRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired WarehouseRepository warehouseRepository;

    @Test
    void materialSearchFiltersByManufacturer() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Material expected = material("MAT-MAN-" + suffix, "Acme Filters " + suffix);
        materialAttributeRepository.save(MaterialAttribute.builder()
            .materialId(expected.getId())
            .key("Rang")
            .value("Qora")
            .sortOrder(0)
            .isDeleted(false)
            .build());
        material("MAT-OTHER-" + suffix, "Other Maker " + suffix);

        mockMvc.perform(get("/api/materials")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("manufacturer", "Acme Filters " + suffix))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(expected.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].manufacturer").value("Acme Filters " + suffix))
            .andExpect(jsonPath("$.data.content[0].attributes").isArray())
            .andExpect(jsonPath("$.data.content[0].attributes.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].attributes[0].key").value("Rang"))
            .andExpect(jsonPath("$.data.content[0].attributes[0].value").value("Qora"));
    }

    @Test
    void materialSearchMatchesMxikCode() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String mxikCode = "MXIKSEARCH-" + suffix;
        Material expected = material("MAT-CODE-" + suffix, "Code Maker " + suffix);
        expected.setMxikCode(mxikCode);
        materialRepository.save(expected);
        material("MAT-OTHER-" + suffix, "Other Maker " + suffix);

        mockMvc.perform(get("/api/materials")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("search", mxikCode))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(expected.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].mxikCode").value(mxikCode));
    }

    @Test
    void materialSearchCanSortByLocalizedNameWithMxikJoin() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        material("MAT-SORT-" + suffix, "Sort Maker " + suffix);

        mockMvc.perform(get("/api/materials")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("search", "MAT-SORT-" + suffix)
                .param("sort", "name,asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void materialSearchCanIncludeMinStockRunwayAdvice() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Warehouse warehouse = warehouse("WH-ADVICE-" + suffix);
        Material expected = material("MAT-ADVICE-" + suffix, "Advice Maker " + suffix);
        expected.setMinStock(new BigDecimal("10"));
        expected.setReplenishmentLeadTimeDays(5);
        expected = materialRepository.save(expected);
        stockBalance(expected, warehouse, new BigDecimal("20"));
        stockMovement(expected, warehouse, new BigDecimal("50"));

        mockMvc.perform(get("/api/materials")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("code", expected.getCode())
                .param("includeAdvice", "true")
                .param("advicePeriodDays", "10")
                .param("adviceWarehouseId", warehouse.getId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].replenishmentLeadTimeDays").value(5))
            .andExpect(jsonPath("$.data.content[0].advice.availableQuantity").value(20))
            .andExpect(jsonPath("$.data.content[0].advice.minStock").value(10))
            .andExpect(jsonPath("$.data.content[0].advice.averageDailyUsage").value(5.0000))
            .andExpect(jsonPath("$.data.content[0].advice.daysUntilMinStock").value(2))
            .andExpect(jsonPath("$.data.content[0].advice.leadTimeGapDays").value(3))
            .andExpect(jsonPath("$.data.content[0].advice.status").value("URGENT"));
    }

    @Test
    void subtypeFiltersSelectEquipmentAnimalsAndLegacyMaterialsWithinTmb() throws Exception {
        String prefix = "SUBTYPE-" + UUID.randomUUID().toString().substring(0, 8);
        for (MaterialSubtype subtype : MaterialSubtype.values()) {
            Material item = material(prefix + "-" + subtype, "Maker");
            if (subtype != MaterialSubtype.MATERIAL) {
                item.setMaterialSubtype(subtype);
                materialRepository.save(item);
            }
        }
        for (MaterialSubtype subtype : MaterialSubtype.values()) {
            mockMvc.perform(get("/api/materials").with(user("admin").roles("ADMIN"))
                    .header("X-Tenant-Id", "default").param("search", prefix)
                    .param("materialType", "TMB").param("materialSubtype", subtype.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].materialType").value("TMB"))
                .andExpect(jsonPath("$.data.content[0].materialSubtype").value(subtype.name()));
        }
        mockMvc.perform(get("/api/materials").with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default").param("search", prefix).param("materialType", "TMB"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(MaterialSubtype.values().length));
        mockMvc.perform(get("/api/materials").with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default").header("Accept-Language", "ru")
                .param("materialSubtype", "BAD"))
            .andExpect(status().isBadRequest());
    }

    private Material material(String code, String manufacturer) {
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        material.setManufacturer(manufacturer);
        return materialRepository.save(material);
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private void stockBalance(Material material, Warehouse warehouse, BigDecimal quantity) {
        StockBalance balance = new StockBalance();
        balance.setCode("BAL-" + UUID.randomUUID().toString().substring(0, 8));
        balance.setName("Advice balance");
        balance.setStatus("ACTIVE");
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setQuantity(quantity);
        balance.setAvailableQuantity(quantity);
        balance.setBalanceAt(LocalDateTime.now());
        stockBalanceRepository.save(balance);
    }

    private void stockMovement(Material material, Warehouse warehouse, BigDecimal quantity) {
        StockMovement movement = new StockMovement();
        movement.setCode("MOV-" + UUID.randomUUID().toString().substring(0, 8));
        movement.setName("Advice issue");
        movement.setStatus(StockMovementStatus.POSTED.name());
        movement.setMaterialId(material.getId());
        movement.setWarehouseId(warehouse.getId());
        movement.setMovementType(MovementType.ISSUE);
        movement.setQuantity(quantity);
        movement.setPostedAt(LocalDateTime.now().minusDays(1));
        stockMovementRepository.save(movement);
    }
}

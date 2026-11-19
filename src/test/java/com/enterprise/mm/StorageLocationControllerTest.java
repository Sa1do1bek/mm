package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.StorageZoneType;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
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
class StorageLocationControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StorageLocationRepository storageLocationRepository;

    @Test
    void storageLocationSearchFiltersByWarehouseAndZoneType() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Warehouse warehouseA = warehouse("WH-LOC-FILTER-A-" + suffix);
        Warehouse warehouseB = warehouse("WH-LOC-FILTER-B-" + suffix);
        StorageLocation expected = storageLocation("LOC-FILTER-EXPECTED-" + suffix, warehouseA, StorageZoneType.PICKING, "COLD-" + suffix);
        storageLocation("LOC-FILTER-WRONG-ZONE-" + suffix, warehouseA, StorageZoneType.STORAGE, "COLD-" + suffix);
        storageLocation("LOC-FILTER-WRONG-WH-" + suffix, warehouseB, StorageZoneType.PICKING, "HOT-" + suffix);

        mockMvc.perform(get("/api/storage-locations")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("warehouseId", warehouseA.getId().toString())
                .param("storageZoneType", StorageZoneType.PICKING.name())
                .param("temperatureZone", "COLD-" + suffix))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(expected.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].warehouseId").value(warehouseA.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].storageZoneType").value(StorageZoneType.PICKING.name()));
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private StorageLocation storageLocation(String code, Warehouse warehouse, StorageZoneType storageZoneType, String temperatureZone) {
        StorageLocation location = new StorageLocation();
        location.setCode(code);
        location.setName(code);
        location.setStatus("ACTIVE");
        location.setWarehouseId(warehouse.getId());
        location.setStorageZoneType(storageZoneType);
        location.setTemperatureZone(temperatureZone);
        return storageLocationRepository.save(location);
    }
}

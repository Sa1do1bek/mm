package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.StorageZoneType;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StorageBinControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StorageLocationRepository storageLocationRepository;
    @Autowired StorageBinRepository storageBinRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;

    private Warehouse warehouse;
    private StorageLocation storageLocation;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        warehouse = new Warehouse();
        warehouse.setCode("WH-BIN-CTRL-" + suffix);
        warehouse.setName(warehouse.getCode());
        warehouse.setStatus("ACTIVE");
        warehouse = warehouseRepository.save(warehouse);

        storageLocation = new StorageLocation();
        storageLocation.setCode("LOC-BIN-CTRL-" + suffix);
        storageLocation.setName(storageLocation.getCode());
        storageLocation.setStatus("ACTIVE");
        storageLocation.setWarehouseId(warehouse.getId());
        storageLocation = storageLocationRepository.save(storageLocation);
    }

    @Test
    void capacityUnitChoicesExcludeOtherCompaniesTenantsAndDeletedUnits() throws Exception {
        String prefix = "BIN-UOM-" + UUID.randomUUID();
        UnitOfMeasure own = capacityUnit(prefix + "-OWN", storageLocation.getCompanyId(), "default", false);
        capacityUnit(prefix + "-OTHER-COMPANY", UUID.randomUUID(), "default", false);
        capacityUnit(prefix + "-OTHER-TENANT", storageLocation.getCompanyId(), "other-tenant", false);
        capacityUnit(prefix + "-OTHER-BOTH", UUID.randomUUID(), "other-tenant", false);
        capacityUnit(prefix + "-DELETED", storageLocation.getCompanyId(), "default", true);

        mockMvc.perform(get("/api/uom")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", storageLocation.getCompanyId().toString())
                .param("search", prefix))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(own.getId().toString()));
    }

    private UnitOfMeasure capacityUnit(String code, UUID companyId, String tenantId, boolean deleted) {
        UnitOfMeasure unit = new UnitOfMeasure();
        unit.setCode(code);
        unit.setName(code);
        unit.setCompanyId(companyId);
        unit.setTenantId(tenantId);
        unit.setDeleted(deleted);
        return unitOfMeasureRepository.save(unit);
    }

    @Test
    void storageBinEndpointSupportsLifecycle() throws Exception {
        String code = "BIN-CTRL-" + UUID.randomUUID().toString().substring(0, 8);
        String createBody = """
            {"code":"%s","name":"Rack A Cell 01","status":"ACTIVE","storageLocationId":"%s","binCode":"A-01","warehouseId":"%s"}
            """.formatted(code, storageLocation.getId(), warehouse.getId());

        String createResponse = mockMvc.perform(post("/api/storage-bins")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.code").value(code))
            .andExpect(jsonPath("$.data.binCode").value("A-01"))
            .andExpect(jsonPath("$.data.warehouseId").value(warehouse.getId().toString()))
            .andExpect(jsonPath("$.data.storageLocationId").value(storageLocation.getId().toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(createResponse).path("data");
        String id = data.path("id").asText();

        mockMvc.perform(get("/api/storage-bins")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("storageLocationId", storageLocation.getId().toString())
                .param("search", "A-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].id").value(id));

        mockMvc.perform(get("/api/storage-bins/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id));

        String updateBody = """
            {"code":"%s","name":"Rack A Cell 02","status":"ACTIVE","storageLocationId":"%s","binCode":"A-02","warehouseId":"%s"}
            """.formatted(code, storageLocation.getId(), warehouse.getId());
        mockMvc.perform(put("/api/storage-bins/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Rack A Cell 02"))
            .andExpect(jsonPath("$.data.binCode").value("A-02"));

        mockMvc.perform(delete("/api/storage-bins/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/storage-bins/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/storage-bins/" + id + "/restore")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deleted").value(false));
    }

    @Test
    void storageBinCreateRequiresStorageLocation() throws Exception {
        mockMvc.perform(post("/api/storage-bins")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"BIN-MISSING-LOC","name":"Missing location","status":"ACTIVE","binCode":"M-01"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void storageBinCapacitySavesValueAndUomTogether() throws Exception {
        UnitOfMeasure kilogram = new UnitOfMeasure();
        kilogram.setCode("KG-BIN-" + UUID.randomUUID().toString().substring(0, 8));
        kilogram.setName("Kilogram");
        kilogram.setMeasurementNameShort("kg");
        kilogram = unitOfMeasureRepository.save(kilogram);

        String body = """
            {"code":"BIN-CAP-%s","name":"Capacity bin","storageLocationId":"%s", "binCode":"CAP-01",
             "capacityQuantity":50,"capacityUomId":"%s"}
            """.formatted(UUID.randomUUID().toString().substring(0, 8), storageLocation.getId(), kilogram.getId());

        mockMvc.perform(post("/api/storage-bins")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.capacityQuantity").value(50))
            .andExpect(jsonPath("$.data.capacityUomId").value(kilogram.getId().toString()))
            .andExpect(jsonPath("$.data.capacityUomCode").value(kilogram.getCode()))
            .andExpect(jsonPath("$.data.capacityUomLabel").value("kg"));
    }

    @Test
    void storageBinCapacityRejectsValueWithoutUom() throws Exception {
        String body = """
            {"code":"BIN-CAP-%s","name":"Capacity bin","storageLocationId":"%s", "binCode":"CAP-02",
             "capacityQuantity":50}
            """.formatted(UUID.randomUUID().toString().substring(0, 8), storageLocation.getId());

        mockMvc.perform(post("/api/storage-bins")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value("Storage bin capacity requires both a value and a unit"));
    }

    @Test
    void storageBinSearchFiltersByWarehouseLocationAndLocationType() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Warehouse warehouseA = warehouse("WH-BIN-FILTER-A-" + suffix);
        Warehouse warehouseB = warehouse("WH-BIN-FILTER-B-" + suffix);
        StorageLocation pickingLocation = storageLocation("LOC-BIN-FILTER-PICK-" + suffix, warehouseA, StorageZoneType.PICKING);
        StorageLocation storageLocation = storageLocation("LOC-BIN-FILTER-STOR-" + suffix, warehouseA, StorageZoneType.STORAGE);
        StorageLocation otherWarehouseLocation = storageLocation("LOC-BIN-FILTER-OTHER-" + suffix, warehouseB, StorageZoneType.PICKING);
        StorageBin expected = storageBin("BIN-FILTER-EXPECTED-" + suffix, warehouseA, pickingLocation);
        storageBin("BIN-FILTER-WRONG-LOC-" + suffix, warehouseA, storageLocation);
        storageBin("BIN-FILTER-WRONG-WH-" + suffix, warehouseB, otherWarehouseLocation);

        mockMvc.perform(get("/api/storage-bins")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("warehouseId", warehouseA.getId().toString())
                .param("storageLocationId", pickingLocation.getId().toString())
                .param("storageZoneType", StorageZoneType.PICKING.name()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(expected.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].warehouseId").value(warehouseA.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].storageLocationId").value(pickingLocation.getId().toString()));
    }

    private Warehouse warehouse(String code) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private StorageLocation storageLocation(String code, Warehouse warehouse, StorageZoneType storageZoneType) {
        StorageLocation location = new StorageLocation();
        location.setCode(code);
        location.setName(code);
        location.setStatus("ACTIVE");
        location.setWarehouseId(warehouse.getId());
        location.setStorageZoneType(storageZoneType);
        return storageLocationRepository.save(location);
    }

    private StorageBin storageBin(String code, Warehouse warehouse, StorageLocation storageLocation) {
        StorageBin bin = new StorageBin();
        bin.setCode(code);
        bin.setName(code);
        bin.setStatus("ACTIVE");
        bin.setWarehouseId(warehouse.getId());
        bin.setStorageLocationId(storageLocation.getId());
        bin.setBinCode(code);
        return storageBinRepository.save(bin);
    }
}

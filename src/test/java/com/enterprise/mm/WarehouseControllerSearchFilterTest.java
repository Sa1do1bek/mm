package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.entity.Warehouse;
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
class WarehouseControllerSearchFilterTest {
    @Autowired MockMvc mockMvc;
    @Autowired WarehouseRepository warehouseRepository;

    @Test
    void warehouseSearchFiltersByAddress() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Warehouse expected = warehouse("WH-ADDR-OK-" + suffix, "Address Match " + suffix);
        warehouse("WH-ADDR-NO-" + suffix, "Different Address " + suffix);

        mockMvc.perform(get("/api/warehouses")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .param("address", "Address Match " + suffix))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(expected.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].address").value("Address Match " + suffix));
    }

    private Warehouse warehouse(String code, String address) {
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        warehouse.setAddress(address);
        return warehouseRepository.save(warehouse);
    }
}

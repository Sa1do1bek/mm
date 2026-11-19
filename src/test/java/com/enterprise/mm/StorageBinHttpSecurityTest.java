package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class StorageBinHttpSecurityTest {
    @Autowired MockMvc mockMvc;

    @Test
    void supplierManagerCannotCreateStorageBin() throws Exception {
        mockMvc.perform(post("/api/storage-bins")
                .with(user("supplier-manager").roles("SUPPLIER_MANAGER"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"FORBIDDEN\",\"storageLocationId\":\"00000000-0000-0000-0000-000000000001\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void storageBinWritesUseWarehouseRoleBoundary() throws Exception {
        String security = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/security/SecurityConfig.java"
        ));
        String warehouseGroup = security.substring(
            security.indexOf("private static final String[] WAREHOUSE"),
            security.indexOf("private static final String[] QUALITY")
        );
        String warehouseWriteMatcher = security.lines()
            .filter(line -> line.contains(".requestMatchers(WAREHOUSE).hasAnyRole("))
            .findFirst()
            .orElseThrow();

        assertThat(warehouseGroup).contains("\"/api/storage-bins/**\"");
        assertThat(warehouseWriteMatcher).doesNotContain("\"SUPPLIER_MANAGER\"");
    }

    @Test
    void warehouseKeeperCanReadUomCatalogForBinCapacity() throws Exception {
        mockMvc.perform(get("/api/uom")
                .with(user("warehouse-keeper").roles("WAREHOUSE_KEEPER"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());
    }
}

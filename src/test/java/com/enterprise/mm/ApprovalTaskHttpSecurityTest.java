package com.enterprise.mm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ApprovalTaskHttpSecurityTest {
    @Autowired MockMvc mockMvc;

    @Test
    void authenticatedNonManagerApproverReachesDomainAuthorizationLayer() throws Exception {
        mockMvc.perform(post("/api/approval-tasks/" + UUID.randomUUID() + "/approve")
                .with(user("assigned-worker").roles("DEPARTMENT_WORKER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isNotFound());
    }

    @Test
    void authenticatedWarehouseAssigneeCanReadTheirOwnScopedWorklist() throws Exception {
        mockMvc.perform(get("/api/approval-tasks/my")
                .with(user("warehouse-assignee").roles("WAREHOUSE_KEEPER"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk());
    }

    @Test
    void warehouseHeadCanReachStockMovementApprovalTimeline() throws Exception {
        mockMvc.perform(get("/api/approval-instances/current")
                .with(user("warehouse-head").roles("WAREHOUSE_HEAD"))
                .header("X-Tenant-Id", "default")
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("moduleKey", "STOCK_MOVEMENT")
                .param("entityId", UUID.randomUUID().toString()))
            .andExpect(status().isNotFound());
    }
}

package com.enterprise.mm.quality.workspace;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IncomingQualityWorkspaceControllerTest {
    private static final String TENANT = "tenant-incoming-quality-controller";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;

    private UUID companyId;
    private QualityInspection inspection;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        Supplier supplier = persist(base(new Supplier(), "SUP-CTRL", "Controller supplier", "ACTIVE"));
        Material material = persist(base(new Material(), "MAT-CTRL", "Controller material", "ACTIVE"));
        Warehouse warehouse = persist(base(new Warehouse(), "WH-CTRL", "Controller warehouse", "ACTIVE"));
        inspection = base(new QualityInspection(), "IQ-CTRL", "Controller inspection", "DRAFT");
        inspection.setInspectionNumber("IQ-CTRL");
        inspection.setInspectionType("INCOMING");
        inspection.setSupplierId(supplier.getId());
        inspection.setMaterialId(material.getId());
        inspection.setWarehouseId(warehouse.getId());
        inspection.setReceivedQuantity(BigDecimal.TEN);
        inspection.setDueDate(LocalDate.now().plusDays(1));
        persist(inspection);
        entityManager.clear();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void exposesSummaryListAndDetailUsingApiResponseConventions() throws Exception {
        mockMvc.perform(get("/api/workspaces/incoming-quality/summary")
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", companyId)
                .with(user("quality-viewer").roles("VIEWER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(7))
            .andExpect(jsonPath("$.data[0].key").value("OPEN"));

        mockMvc.perform(get("/api/workspaces/incoming-quality/inspections")
                .param("queue", "AWAITING_QUARANTINE")
                .param("page", "0")
                .param("size", "25")
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", companyId)
                .with(user("quality-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.queueKey").value("AWAITING_QUARANTINE"))
            .andExpect(jsonPath("$.data.items[0].inspectionNumber").value("IQ-CTRL"))
            .andExpect(jsonPath("$.data.items[0].supplier.displayName").value("Controller supplier"));

        mockMvc.perform(get("/api/workspaces/incoming-quality/inspections/{id}", inspection.getId())
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", companyId)
                .with(user("quality-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.header.inspectionNumber").value("IQ-CTRL"))
            .andExpect(jsonPath("$.data.header.material.displayName").value("Controller material"))
            .andExpect(jsonPath("$.data.allowedActions[0].code").exists());
    }

    @Test
    void rejectsAUserOutsideTheExistingQualityReadRoles() throws Exception {
        mockMvc.perform(get("/api/workspaces/incoming-quality/summary")
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", companyId)
                .with(user("executive").roles("EXECUTIVE")))
            .andExpect(status().isForbidden());
    }

    private <T extends BusinessEntity> T base(T entity, String code, String name, String status) {
        entity.setTenantId(TENANT);
        entity.setCompanyId(companyId);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus(status);
        return entity;
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}

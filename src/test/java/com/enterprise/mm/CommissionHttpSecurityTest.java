package com.enterprise.mm;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.payload.CommissionSearchRequest;
import com.enterprise.mm.service.CommissionService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:commission_http_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class CommissionHttpSecurityTest {
    private static final String TENANT = "tenant-commission-security";
    private static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Autowired MockMvc mvc;
    @MockBean CommissionService commissionService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(commissionService.search(any(CommissionSearchRequest.class), any(Pageable.class)))
            .thenReturn(Page.empty());
    }

    @Test
    void procurementSpecialistCanReadCommissionSelectorsButCannotAdministerCommissions() throws Exception {
        mvc.perform(get("/api/commissions")
                .with(user("buyer").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID))
            .andExpect(status().isOk());

        mvc.perform(post("/api/commissions")
                .with(user("buyer").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_ID)
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }
}

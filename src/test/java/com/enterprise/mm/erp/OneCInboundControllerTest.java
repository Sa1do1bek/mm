package com.enterprise.mm.erp;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.GlobalExceptionHandler;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class OneCInboundControllerTest {
    private static final String KEY="test-native-key-with-at-least-32-characters";
    private static final UUID COMPANY=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final OneCInboundService service=mock(OneCInboundService.class);
    private final OneCApiAccess access=new OneCApiAccess("http://127.0.0.1:15442/compact/hs/mmapi",KEY,"mm-local",COMPANY.toString());
    private final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders
        .standaloneSetup(new OneCInboundController(access,service))
        .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }

    @Test void missingOrWrongKeyReturnsUnauthorizedWithoutCallingStockImport() throws Exception {
        TenantContext.setTenant("mm-local"); CompanyContext.setCompany(COMPANY);
        mvc.perform(post("/api/integrations/one-c/events").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/integrations/one-c/events").header("X-MM-API-Key","wrong").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void wrongScopeReturnsUnauthorized() throws Exception {
        TenantContext.setTenant("different"); CompanyContext.setCompany(COMPANY);
        mvc.perform(post("/api/integrations/one-c/events").header("X-MM-API-Key",KEY).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void authenticatedImportHasIntegrationActorAndRestoresPreviousContext() throws Exception {
        TenantContext.setTenant("mm-local"); CompanyContext.setCompany(COMPANY);
        var previous=new UsernamePasswordAuthenticationToken("previous",null);
        SecurityContextHolder.getContext().setAuthentication(previous);
        when(service.receive(any(),eq("event"))).thenAnswer(call -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("one-c-api");
            return Map.of("status","APPLIED");
        });
        mvc.perform(post("/api/integrations/one-c/events").header("X-MM-API-Key",KEY).header("Idempotency-Key","event")
            .contentType("application/json").content("{}"))
            .andExpect(status().isOk());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(previous);
    }

    @Test void nativeConnectionProbeChecksKeyAndCompanyWithoutImportingAnything() throws Exception {
        TenantContext.setTenant("mm-local"); CompanyContext.setCompany(COMPANY);
        mvc.perform(get("/api/integrations/one-c/health"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/integrations/one-c/health").header("X-MM-API-Key",KEY))
            .andExpect(status().isOk()).andExpect(jsonPath("connected").value(true))
            .andExpect(jsonPath("tenantId").value("mm-local"))
            .andExpect(jsonPath("companyId").value(COMPANY.toString()));
        CompanyContext.setCompany(UUID.randomUUID());
        mvc.perform(get("/api/integrations/one-c/health").header("X-MM-API-Key",KEY))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}

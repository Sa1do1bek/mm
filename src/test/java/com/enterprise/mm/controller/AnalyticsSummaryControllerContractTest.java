package com.enterprise.mm.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.service.AnalyticsSummaryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnalyticsSummaryControllerContractTest {

    @Test
    void exposesCanonicalDashboardIntegrationContract() throws Exception {
        AnalyticsSummaryService service = mock(AnalyticsSummaryService.class);
        when(service.companySummary()).thenReturn(Map.of("metrics", Map.of()));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AnalyticsSummaryController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .build();

        mockMvc.perform(get("/api/analytics/dashboard/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.moduleCode").value("MM"))
                .andExpect(jsonPath("$.scope.organizationId").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.sourceRevision").isNumber())
                .andExpect(jsonPath("$.freshness.status").value("FRESH"))
                .andExpect(jsonPath("$.datasets[0].type").value("mm.material-directory.v1"));
    }
}

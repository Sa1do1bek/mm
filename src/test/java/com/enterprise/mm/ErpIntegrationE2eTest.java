package com.enterprise.mm;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ErpIntegrationE2eTest {
    @Autowired MockMvc mockMvc;

    @Test
    void erpE2eSmokeReportsInternalErpSuitesWithoutLiveDispatch() throws Exception {
        mockMvc.perform(post("/api/integrations/erp-e2e/run")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "systems":["FI","HR","EDO"],
                      "requireLive":false,
                      "dispatchOutbound":false,
                      "syncInbound":true,
                      "archiveOriginals":false,
                      "limit":5
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"systemsProcessed\":3")))
            .andExpect(content().string(containsString("\"suite\":\"UZASSETS\"")))
            .andExpect(content().string(containsString("\"suite\":\"HH\"")))
            .andExpect(content().string(containsString("\"suite\":\"DOCFLOW\"")))
            .andExpect(content().string(containsString("\"outboundPublished\":0")));
    }
}

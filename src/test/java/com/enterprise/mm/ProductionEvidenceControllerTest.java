package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionEvidenceControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void productionEvidenceSupportsReviewChecklistExportsDeleteAndRestore() throws Exception {
        String code = "PE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String createResponse = mockMvc.perform(post("/api/production-evidence")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "name":"Production go-live evidence",
                      "status":"DRAFT",
                      "description":"Manual production readiness packet",
                      "evidenceType":"MANUAL",
                      "runId":"run-%s",
                      "environmentName":"staging",
                      "baseUrl":"https://mm.example.test",
                      "evidenceSource":"MANUAL",
                      "overallStatus":"WARN",
                      "productionReady":false,
                      "blocksProductionCount":1,
                      "warningsCount":2,
                      "checksJson":"[]",
                      "evidenceJson":"{\\"schemaVersion\\":1}"
                    }
                    """.formatted(code, code)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.code").value(code))
            .andExpect(jsonPath("$.data.overallStatus").value("WARN"))
            .andExpect(jsonPath("$.data.status").value("WARN"))
            .andExpect(jsonPath("$.data.productionReady").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode created = objectMapper.readTree(createResponse).path("data");
        String id = created.path("id").asText();

        mockMvc.perform(put("/api/production-evidence/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"%s",
                      "name":"Production go-live evidence accepted",
                      "status":"PASS",
                      "evidenceType":"MANUAL",
                      "runId":"run-%s",
                      "environmentName":"production",
                      "baseUrl":"https://mm.example.test",
                      "evidenceSource":"MANUAL",
                      "overallStatus":"PASS",
                      "productionReady":true,
                      "blocksProductionCount":0,
                      "warningsCount":0,
                      "checksJson":"[]",
                      "evidenceJson":"{\\"schemaVersion\\":2}"
                    }
                    """.formatted(code, code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Production go-live evidence accepted"))
            .andExpect(jsonPath("$.data.overallStatus").value("PASS"))
            .andExpect(jsonPath("$.data.productionReady").value(true));

        mockMvc.perform(post("/api/production-evidence/" + id + "/security-checklist")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pentestStatus":"passed",
                      "dependencyAuditStatus":"ok",
                      "tlsCipherScanStatus":"accepted",
                      "backupAccessReviewStatus":"failed",
                      "notes":"dependency audit and TLS reviewed"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.pentestStatus").value("PASS"))
            .andExpect(jsonPath("$.data.dependencyAuditStatus").value("PASS"))
            .andExpect(jsonPath("$.data.tlsCipherScanStatus").value("ACCEPTED"))
            .andExpect(jsonPath("$.data.backupAccessReviewStatus").value("FAIL"))
            .andExpect(jsonPath("$.data.securityChecklistJson").isString());

        mockMvc.perform(post("/api/production-evidence/" + id + "/accept")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "acceptedBy":"release-manager",
                      "monitoringAcceptedBy":"sre-lead",
                      "notes":"approved for staged rollout"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.acceptedBy").value("release-manager"))
            .andExpect(jsonPath("$.data.monitoringAcceptedBy").value("sre-lead"))
            .andExpect(jsonPath("$.data.acceptanceNotes").value("approved for staged rollout"));

        byte[] pdf = mockMvc.perform(get("/api/production-evidence/" + id + "/go-live.pdf")
                .with(user("auditor").roles("AUDITOR"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=go-live-readiness.pdf"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        byte[] xlsx = mockMvc.perform(get("/api/production-evidence/" + id + "/go-live.xlsx")
                .with(user("auditor").roles("AUDITOR"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=go-live-readiness.xlsx"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        assertThat(new String(xlsx, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");

        mockMvc.perform(delete("/api/production-evidence/" + id)
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/production-evidence/" + id)
                .with(user("auditor").roles("AUDITOR"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/production-evidence/" + id + "/restore")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deleted").value(false));
    }
}

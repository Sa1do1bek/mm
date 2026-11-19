package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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
class ReferenceMappingTest {
    private static final UUID SECOND_COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void referenceMappingsAreUniqueInsideCompanyAndIsolatedBetweenCompanies() throws Exception {
        String externalCode = "1C-MAP-" + UUID.randomUUID().toString().substring(0, 8);
        String payload = payload(externalCode, "FINANCIAL");

        JsonNode first = postMapping(payload, CompanyContext.DEFAULT_COMPANY_ID)
            .path("data");

        assertThat(first.path("referenceEntityType").asText()).isEqualTo("MATERIAL");
        assertThat(first.path("externalSystem").asText()).isEqualTo("ONE_C");
        assertThat(first.path("externalCode").asText()).isEqualTo(externalCode);
        assertThat(first.path("accountingArea").asText()).isEqualTo("FINANCIAL");
        assertThat(first.path("companyId").asText()).isEqualTo(CompanyContext.DEFAULT_COMPANY_ID.toString());

        mockMvc.perform(post("/api/reference-mappings")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest());

        JsonNode second = postMapping(payload, SECOND_COMPANY_ID)
            .path("data");
        assertThat(second.path("companyId").asText()).isEqualTo(SECOND_COMPANY_ID.toString());
        assertThat(second.path("externalCode").asText()).isEqualTo(externalCode);

        JsonNode defaultCompanySearch = objectMapper.readTree(mockMvc.perform(get("/api/reference-mappings")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("search", externalCode)
                .param("size", "20"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
        assertThat(defaultCompanySearch.path("data").path("content")).hasSize(1);
        assertThat(defaultCompanySearch.path("data").path("content").get(0).path("companyId").asText())
            .isEqualTo(CompanyContext.DEFAULT_COMPANY_ID.toString());

        JsonNode secondCompanySearch = objectMapper.readTree(mockMvc.perform(get("/api/reference-mappings")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", SECOND_COMPANY_ID.toString())
                .param("search", externalCode)
                .param("size", "20"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
        assertThat(secondCompanySearch.path("data").path("content")).hasSize(1);
        assertThat(secondCompanySearch.path("data").path("content").get(0).path("companyId").asText())
            .isEqualTo(SECOND_COMPANY_ID.toString());
    }

    @Test
    void sameExternalCodeCanBeMappedToDifferentAccountingAreas() throws Exception {
        String externalCode = "WMS-MAP-" + UUID.randomUUID().toString().substring(0, 8);

        postMapping(payload(externalCode, "WAREHOUSE"), CompanyContext.DEFAULT_COMPANY_ID);
        JsonNode financial = postMapping(payload(externalCode, "FINANCIAL"), CompanyContext.DEFAULT_COMPANY_ID)
            .path("data");

        assertThat(financial.path("externalCode").asText()).isEqualTo(externalCode);
        assertThat(financial.path("accountingArea").asText()).isEqualTo("FINANCIAL");
    }

    @Test
    void invalidReferenceIdIsRejected() throws Exception {
        String payload = """
            {
              "referenceEntityType": "material",
              "referenceId": "%s",
              "externalSystem": "one-c",
              "externalCode": "BAD-MAP-%s",
              "accountingArea": "management"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/reference-mappings")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest());
    }

    @Test
    void generateDefaultsAndExportReferenceMappings() throws Exception {
        JsonNode generated = objectMapper.readTree(mockMvc.perform(post("/api/reference-mappings/generate-defaults")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("externalSystem", "one-c")
                .param("accountingArea", "management"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
        assertThat(generated.path("data").path("applied").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(generated.path("data").path("failures").isArray()).isTrue();

        JsonNode search = objectMapper.readTree(mockMvc.perform(get("/api/reference-mappings")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("referenceEntityType", "material")
                .param("externalSystem", "one-c")
                .param("accountingArea", "management")
                .param("size", "50"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
        assertThat(search.path("data").path("content")).isNotEmpty();

        byte[] xlsx = mockMvc.perform(get("/api/reports/reference-mappings.xlsx")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        assertThat(xlsx).hasSizeGreaterThan(100);
    }

    @Test
    void aiAnalysisSuggestsAndAppliesReferenceMappings() throws Exception {
        String externalSystem = "AI_TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String analyzePayload = """
            {
              "referenceEntityType": "MATERIAL",
              "externalSystem": "%s",
              "accountingArea": "MANAGEMENT",
              "providerSystem": "CHATGPT",
              "onlyUnmapped": true,
              "limit": 5
            }
            """.formatted(externalSystem);

        JsonNode analysis = objectMapper.readTree(mockMvc.perform(post("/api/reference-mappings/ai/analyze")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(analyzePayload))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()).path("data");

        assertThat(analysis.path("providerSystem").asText()).isEqualTo("CHATGPT");
        assertThat(analysis.path("suggestions")).isNotEmpty();
        assertThat(analysis.path("suggestions").get(0).path("externalCode").asText()).isNotBlank();

        String applyPayload = """
            {
              "referenceEntityType": "MATERIAL",
              "externalSystem": "%s",
              "accountingArea": "MANAGEMENT",
              "suggestions": [%s]
            }
            """.formatted(externalSystem, analysis.path("suggestions").get(0));

        JsonNode applied = objectMapper.readTree(mockMvc.perform(post("/api/reference-mappings/ai/apply")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyPayload))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()).path("data");

        assertThat(applied.path("created").asInt() + applied.path("updated").asInt()).isGreaterThan(0);
        assertThat(applied.path("applied").asInt())
            .isEqualTo(applied.path("created").asInt() + applied.path("updated").asInt());
        assertThat(applied.path("failures").isArray()).isTrue();
        assertThat(applied.path("mappings")).isNotEmpty();
    }

    @Test
    void mxikCodeMappingsCanBeCreatedByReferenceCodeOnly() throws Exception {
        String mxikCode = "MXIK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        mockMvc.perform(post("/api/mxik/create")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Mapped MXIK",
                      "nameRu":"Mapped MXIK",
                      "kod":"%s",
                      "type":"GOODS"
                    }
                    """.formatted(mxikCode)))
            .andExpect(status().isCreated());

        JsonNode mapping = objectMapper.readTree(mockMvc.perform(post("/api/reference-mappings")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "referenceEntityType": "mxik_code",
                      "referenceCode": "%s",
                      "referenceName": "Mapped MXIK",
                      "externalSystem": "one-c",
                      "externalCode": "ONEC-%s",
                      "accountingArea": "management"
                    }
                    """.formatted(mxikCode, mxikCode)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString()).path("data");

        assertThat(mapping.path("referenceEntityType").asText()).isEqualTo("MXIK_CODE");
        assertThat(mapping.path("referenceCode").asText()).isEqualTo(mxikCode);
        assertThat(mapping.path("referenceId").isNull()).isTrue();
    }

    private JsonNode postMapping(String payload, UUID companyId) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/reference-mappings")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
    }

    private String payload(String externalCode, String accountingArea) {
        return """
            {
              "referenceEntityType": "material",
              "referenceCode": "MAT-STEEL-2MM",
              "referenceName": "Steel sheet 2mm",
              "externalSystem": "one-c",
              "externalCode": "%s",
              "externalName": "External steel sheet",
              "accountingArea": "%s",
              "chartOfAccountsCode": "main",
              "accountCode": "1010",
              "subAccountCode": "RAW",
              "costCenter": "prod",
              "currencyCode": "uzs",
              "priority": 10,
              "defaultMapping": true,
              "validFrom": "2026-01-01"
            }
            """.formatted(externalCode, accountingArea);
    }
}

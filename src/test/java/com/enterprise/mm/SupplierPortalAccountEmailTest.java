package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
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
class SupplierPortalAccountEmailTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void customLoginEmailIsNotOverwrittenBySupplierEmail() throws Exception {
        UUID companyId = UUID.randomUUID();
        String supplierEmail = "existing-" + suffix() + "@example.com";
        String customLoginEmail = "new-portal-" + suffix() + "@example.com";

        String supplierId = createSupplier(companyId, supplierEmail);

        String portalPayload = """
            {
              "supplierId": "%s",
              "loginEmail": "%s"
            }
            """.formatted(supplierId, customLoginEmail);

        String responseStr = mockMvc.perform(post("/api/supplier-portal/accounts")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(portalPayload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.loginEmail").value(customLoginEmail.toLowerCase(Locale.ROOT)))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode responseData = objectMapper.readTree(responseStr).path("data");
        assertThat(responseData.path("loginEmail").asText()).isEqualTo(customLoginEmail.toLowerCase(Locale.ROOT));
    }

    @Test
    void blankLoginEmailFallsBackToSupplierEmail() throws Exception {
        UUID companyId = UUID.randomUUID();
        String supplierEmail = "fallback-" + suffix() + "@example.com";

        String supplierId = createSupplier(companyId, supplierEmail);

        String portalPayload = """
            {
              "supplierId": "%s",
              "loginEmail": ""
            }
            """.formatted(supplierId);

        mockMvc.perform(post("/api/supplier-portal/accounts")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(portalPayload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.loginEmail").value(supplierEmail.toLowerCase(Locale.ROOT)));
    }

    @Test
    void duplicateLoginEmailCaseInsensitiveReturnsConflict() throws Exception {
        UUID companyId = UUID.randomUUID();
        String loginEmail = "duplicate-" + suffix() + "@example.com";

        String supplier1Id = createSupplier(companyId, "supp1-" + suffix() + "@example.com");
        String supplier2Id = createSupplier(companyId, "supp2-" + suffix() + "@example.com");

        String payload1 = """
            {
              "supplierId": "%s",
              "loginEmail": "%s"
            }
            """.formatted(supplier1Id, loginEmail.toLowerCase(Locale.ROOT));

        mockMvc.perform(post("/api/supplier-portal/accounts")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload1))
            .andExpect(status().isCreated());

        String payload2 = """
            {
              "supplierId": "%s",
              "loginEmail": "%s"
            }
            """.formatted(supplier2Id, loginEmail.toUpperCase(Locale.ROOT));

        mockMvc.perform(post("/api/supplier-portal/accounts")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload2))
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("Supplier portal login email already exists")));
    }

    private String createSupplier(UUID companyId, String email) throws Exception {
        String supplierPayload = """
            {
              "name": "Supplier %s",
              "status": "ACTIVE",
              "legalName": "Supplier legal %s",
              "taxId": "%s",
              "registrationNumber": "REG-%s",
              "legalAddress": "Tashkent street 1",
              "email": "%s",
              "bankName": "Test Bank",
              "bankMfo": "00444",
              "bankAccount": "%s"
            }
            """.formatted(suffix(), suffix(), productCode(), suffix(), email, "20208000" + productCode());

        String responseStr = mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(supplierPayload))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        return objectMapper.readTree(responseStr).path("data").path("id").asText();
    }

    private String productCode() {
        return "9" + suffix().replaceAll("[^A-Z0-9]", "").substring(0, 11);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
}

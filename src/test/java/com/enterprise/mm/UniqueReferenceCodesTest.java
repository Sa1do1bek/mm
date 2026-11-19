package com.enterprise.mm;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
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
class UniqueReferenceCodesTest {
    private static final UUID SECOND_COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void duplicateIkpuCodesAreRejectedInsideCompanyAndAllowedAcrossCompanies() throws Exception {
        String code = productCode();
        String formattedCode = code.substring(0, 4) + " " + code.substring(4);

        postJson("/api/ikpu-codes", ikpuPayload(formattedCode), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isCreated());

        postJson("/api/ikpu-codes", ikpuPayload(code), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("IKPU code already exists")));

        postJson("/api/ikpu-codes", ikpuPayload(code), SECOND_COMPANY_ID)
            .andExpect(status().isCreated());
    }

    @Test
    void duplicateTnvedCodesAreRejectedInsideCompanyAndAllowedAcrossCompanies() throws Exception {
        String code = productCode();
        String formattedCode = code.substring(0, 4) + " " + code.substring(4, 7) + " " + code.substring(7);

        postJson("/api/tnved-codes", tnvedPayload(formattedCode), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isCreated());

        postJson("/api/tnved-codes", tnvedPayload(code), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("TN VED code already exists")));

        postJson("/api/tnved-codes", tnvedPayload(code), SECOND_COMPANY_ID)
            .andExpect(status().isCreated());
    }

    @Test
    void duplicateChartOfAccountCodesAreRejectedOnCreateAndUpdate() throws Exception {
        String accountCode = "ACC-" + suffix();
        JsonNode first = objectMapper.readTree(postJson("/api/chart-of-accounts", chartPayload(accountCode.toLowerCase(Locale.ROOT)), CompanyContext.DEFAULT_COMPANY_ID)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .path("data");

        postJson("/api/chart-of-accounts", chartPayload(accountCode), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("Chart of account code already exists")));

        postJson("/api/chart-of-accounts", chartPayload(accountCode), SECOND_COMPANY_ID)
            .andExpect(status().isCreated());

        JsonNode other = objectMapper.readTree(postJson("/api/chart-of-accounts", chartPayload(accountCode + "-2"), CompanyContext.DEFAULT_COMPANY_ID)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .path("data");

        putJson("/api/chart-of-accounts/" + other.path("id").asText(), chartPayload(first.path("accountCode").asText()), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("Chart of account code already exists")));
    }

    @Test
    void duplicateSupplierBankAccountsAreRejectedInsideCompanyAndAllowedAcrossCompanies() throws Exception {
        String bankAccount = "20208000" + productCode();
        String formattedBankAccount = bankAccount.substring(0, 5) + " " + bankAccount.substring(5, 13) + " " + bankAccount.substring(13);

        postJson("/api/suppliers", supplierPayload(formattedBankAccount), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isCreated());

        postJson("/api/suppliers", supplierPayload(bankAccount), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("Supplier bank account already exists")));

        postJson("/api/suppliers", supplierPayload(bankAccount), SECOND_COMPANY_ID)
            .andExpect(status().isCreated());
    }

    @Test
    void supplierCanStoreMultipleBankAccountsAndRejectsDuplicateAccountsInPayload() throws Exception {
        String firstAccount = "20208000" + productCode();
        String secondAccount = "20208000" + productCode();

        postJson("/api/suppliers", supplierPayloadWithBankAccounts(firstAccount, secondAccount), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.bankAccount").value(firstAccount))
            .andExpect(jsonPath("$.data.bankAccounts.length()").value(2))
            .andExpect(jsonPath("$.data.bankAccounts[0].bankAccount").value(firstAccount))
            .andExpect(jsonPath("$.data.bankAccounts[0].primaryAccount").value(true))
            .andExpect(jsonPath("$.data.bankAccounts[1].bankAccount").value(secondAccount));

        postJson("/api/suppliers", supplierPayloadWithBankAccounts(firstAccount, firstAccount), SECOND_COMPANY_ID)
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("Supplier bank account already exists")));
    }

    @Test
    void supplierUpdateKeepsUnchangedBankAccountsWithoutUniqueConstraintConflict() throws Exception {
        String firstAccount = "20208000" + productCode();
        String secondAccount = "20208000" + productCode();
        JsonNode supplier = objectMapper.readTree(postJson(
                "/api/suppliers",
                supplierPayloadWithBankAccounts(firstAccount, secondAccount),
                CompanyContext.DEFAULT_COMPANY_ID)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .path("data");

        String updatePayload = """
            {
              "expectedVersion": %d,
              "name": "Updated supplier name",
              "legalName": "Updated supplier legal name",
              "taxId": "%s",
              "registrationNumber": "REG-%s",
              "legalAddress": "Updated legal address",
              "email": "updated-%s@example.uz",
              "bankAccounts": [
                {
                  "bankName": "Updated Primary Bank",
                  "bankMfo": "00444",
                  "bankAccount": "%s",
                  "bankAddress": "Updated primary bank address",
                  "primaryAccount": true
                },
                {
                  "bankName": "Secondary Test Bank",
                  "bankMfo": "00555",
                  "bankAccount": "%s",
                  "bankAddress": "Secondary bank address"
                }
              ]
            }
            """.formatted(supplier.path("version").asLong(), supplier.path("taxId").asText(), suffix(), suffix(), firstAccount, secondAccount);

        putJson("/api/suppliers/" + supplier.path("id").asText(), updatePayload, CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Updated supplier name"))
            .andExpect(jsonPath("$.data.bankAccounts.length()").value(2))
            .andExpect(jsonPath("$.data.bankAccounts[0].bankAccount").value(firstAccount))
            .andExpect(jsonPath("$.data.bankAccounts[0].bankName").value("Updated Primary Bank"));
    }

    @Test
    void duplicateSupplierInnIsRejectedInsideCompanyAndAllowedAcrossCompanies() throws Exception {
        String taxId = productCode();
        String formattedTaxId = taxId.substring(0, 3) + " " + taxId.substring(3, 6) + " " + taxId.substring(6);

        postJson("/api/suppliers", supplierPayloadWithTaxId("20208000" + productCode(), formattedTaxId), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isCreated());

        postJson("/api/suppliers", supplierPayloadWithTaxId("20208000" + productCode(), taxId), CompanyContext.DEFAULT_COMPANY_ID)
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("Supplier INN already exists")));

        postJson("/api/suppliers", supplierPayloadWithTaxId("20208000" + productCode(), taxId), SECOND_COMPANY_ID)
            .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String payload, UUID companyId) throws Exception {
        return mockMvc.perform(post(path)
            .with(user("admin").roles("ADMIN"))
            .header("X-Company-Id", companyId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload));
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String path, String payload, UUID companyId) throws Exception {
        return mockMvc.perform(put(path)
            .with(user("admin").roles("ADMIN"))
            .header("X-Company-Id", companyId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload));
    }

    private String ikpuPayload(String code) {
        return """
            {
              "ikpuCode": "%s",
              "name": "Unique IKPU %s",
              "status": "ACTIVE",
              "unitCode": "PCS",
              "sourceSystem": "MANUAL"
            }
            """.formatted(code, suffix());
    }

    private String tnvedPayload(String code) {
        return """
            {
              "tnvedCode": "%s",
              "name": "Unique TN VED %s",
              "status": "ACTIVE",
              "sectionCode": "XV",
              "unitCode": "KG",
              "sourceSystem": "MANUAL"
            }
            """.formatted(code, suffix());
    }

    private String chartPayload(String accountCode) {
        return """
            {
              "accountCode": "%s",
              "name": "Unique account %s",
              "status": "ACTIVE",
              "accountType": "ASSET",
              "sourceSystem": "MANUAL"
            }
            """.formatted(accountCode, suffix());
    }

    private String supplierPayload(String bankAccount) {
        return """
            {
              "name": "Unique supplier %s",
              "status": "ACTIVE",
              "legalName": "Unique supplier legal %s",
              "taxId": "%s",
              "registrationNumber": "REG-%s",
              "legalAddress": "Legal Address %s",
              "email": "supplier-%s@example.uz",
              "bankName": "Test Bank",
              "bankMfo": "00444",
              "bankAccount": "%s"
            }
            """.formatted(suffix(), suffix(), productCode(), suffix(), suffix(), suffix(), bankAccount);
    }

    private String supplierPayloadWithTaxId(String bankAccount, String taxId) {
        return """
            {
              "name": "Unique supplier %s",
              "status": "ACTIVE",
              "legalName": "Unique supplier legal %s",
              "taxId": "%s",
              "registrationNumber": "REG-%s",
              "legalAddress": "Legal Address %s",
              "email": "supplier-%s@example.uz",
              "bankName": "Test Bank",
              "bankMfo": "00444",
              "bankAccount": "%s"
            }
            """.formatted(suffix(), suffix(), taxId, suffix(), suffix(), suffix(), bankAccount);
    }

    private String supplierPayloadWithBankAccounts(String firstAccount, String secondAccount) {
        return """
            {
              "name": "Multi bank supplier %s",
              "status": "ACTIVE",
              "legalName": "Multi bank supplier legal %s",
              "taxId": "%s",
              "registrationNumber": "REG-%s",
              "legalAddress": "Legal Address %s",
              "email": "supplier-%s@example.uz",
              "bankAccounts": [
                {
                  "bankName": "Primary Test Bank",
                  "bankMfo": "00444",
                  "bankAccount": "%s",
                  "bankAddress": "Primary bank address",
                  "primaryAccount": true
                },
                {
                  "bankName": "Secondary Test Bank",
                  "bankMfo": "00555",
                  "bankAccount": "%s",
                  "bankAddress": "Secondary bank address"
                }
              ]
            }
            """.formatted(suffix(), suffix(), productCode(), suffix(), suffix(), suffix(), firstAccount, secondAccount);
    }

    private String productCode() {
        return "9" + suffix().replaceAll("[^A-Z0-9]", "").substring(0, 11);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
}

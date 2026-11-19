package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
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
class SupplierCreateIdempotencyTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SupplierRepository supplierRepository;
    @Autowired SupplierPortalAccountRepository portalAccountRepository;

    @Test
    void sameIdempotencyKeyReplaysSupplierCreate() throws Exception {
        UUID companyId = UUID.randomUUID();
        String idempotencyKey = "supplier-create-key-" + suffix();
        String payload = supplierPayload("Idempotent Supplier " + suffix(), "20208000" + productCode(), productCode());

        String response1Str = mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", TenantContext.DEFAULT_TENANT)
                .header("X-Company-Id", companyId.toString())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode response1 = objectMapper.readTree(response1Str).path("data");
        String createdId = response1.path("id").asText();

        String response2Str = mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", TenantContext.DEFAULT_TENANT)
                .header("X-Company-Id", companyId.toString())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode response2 = objectMapper.readTree(response2Str).path("data");
        String replayedId = response2.path("id").asText();

        assertThat(replayedId).isEqualTo(createdId);

        long count = supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT, companyId
        ).size();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void duplicateBankAccountReturnsConflictAndDoesNotCreateSecondSupplier() throws Exception {
        UUID companyId = UUID.randomUUID();
        String bankAccount = "20208000" + productCode();
        String payload1 = supplierPayload("First Supplier " + suffix(), bankAccount, productCode());

        mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload1))
            .andExpect(status().isCreated());

        long countBefore = supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT, companyId
        ).size();

        String payload2 = supplierPayload("Second Supplier " + suffix(), bankAccount, productCode());

        mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload2))
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("Supplier bank account already exists in this company")));

        long countAfter = supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT, companyId
        ).size();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    void duplicateBranchInnReturnsConflictAndDoesNotCreateSecondSupplier() throws Exception {
        UUID companyId = UUID.randomUUID();
        String baseTaxId = productCode();

        String branchPayload1 = branchSupplierPayload("Branch 1 " + suffix(), baseTaxId, 1);

        mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(branchPayload1))
            .andExpect(status().isCreated());

        long countBefore = supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT, companyId
        ).size();

        String branchPayload2 = branchSupplierPayload("Branch 2 " + suffix(), baseTaxId, 1);

        mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(branchPayload2))
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("Supplier branch INN already exists in this company")));

        long countAfter = supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT, companyId
        ).size();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    void duplicatePortalLoginEmailReturnsConflict() throws Exception {
        UUID companyId = UUID.randomUUID();

        // First create a supplier
        String supplierResStr = mockMvc.perform(post("/api/suppliers")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(supplierPayload("Portal Host " + suffix(), "20208000" + productCode(), productCode())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String supplierId = objectMapper.readTree(supplierResStr).path("data").path("id").asText();

        String portalPayload1 = """
            {
              "supplierId": "%s",
              "loginEmail": "test@example.com"
            }
            """.formatted(supplierId);

        mockMvc.perform(post("/api/supplier-portal/accounts")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(portalPayload1))
            .andExpect(status().isCreated());

        String portalPayload2 = """
            {
              "supplierId": "%s",
              "loginEmail": "TEST@example.com"
            }
            """.formatted(supplierId);

        mockMvc.perform(post("/api/supplier-portal/accounts")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", companyId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(portalPayload2))
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("Supplier portal login email already exists")));
    }

    private String supplierPayload(String name, String bankAccount, String taxId) {
        return """
            {
              "name": "%s",
              "status": "ACTIVE",
              "legalName": "%s legal",
              "taxId": "%s",
              "registrationNumber": "REG-%s",
              "legalAddress": "Tashkent street 1",
              "email": "info-%s@supplier.uz",
              "bankName": "Test Bank",
              "bankMfo": "00444",
              "bankAccount": "%s"
            }
            """.formatted(name, name, taxId, suffix(), suffix(), bankAccount);
    }

    private String branchSupplierPayload(String name, String baseTaxId, int suffixVal) {
        return """
            {
              "name": "%s",
              "status": "ACTIVE",
              "legalName": "%s legal",
              "taxId": "%s_%d",
              "createAsBranch": true,
              "supplierType": "BRANCH",
              "baseTaxId": "%s",
              "taxIdSuffix": %d,
              "registrationNumber": "REG-%s",
              "legalAddress": "Tashkent branch street 2",
              "email": "branch-%s@supplier.uz",
              "bankName": "Test Bank Branch",
              "bankMfo": "00444",
              "bankAccount": "%s"
            }
            """.formatted(name, name, baseTaxId, suffixVal, baseTaxId, suffixVal, suffix(), suffix(), "20208000" + productCode());
    }

    private String productCode() {
        return "9" + suffix().replaceAll("[^A-Z0-9]", "").substring(0, 11);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
}

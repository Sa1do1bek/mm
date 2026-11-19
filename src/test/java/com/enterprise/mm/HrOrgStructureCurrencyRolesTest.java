package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.ExchangeRate;
import com.enterprise.mm.entity.ClassifierType;
import com.enterprise.mm.entity.DepartmentType;
import com.enterprise.mm.entity.Role;
import com.enterprise.mm.repository.ClassifierTypeRepository;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DepartmentTypeRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.ExchangeRateRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.PositionRepository;
import com.enterprise.mm.repository.RoleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
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
class HrOrgStructureCurrencyRolesTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RoleRepository roleRepository;
    @Autowired ClassifierTypeRepository classifierTypeRepository;
    @Autowired CurrencyRepository currencyRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired DepartmentTypeRepository departmentTypeRepository;
    @Autowired PositionRepository positionRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired ExchangeRateRepository exchangeRateRepository;

    @BeforeEach
    void referenceData() {
        String tenant = TenantContext.currentTenant();
        for (RoleName roleName : RoleName.values()) {
            if (roleRepository.findByTenantIdAndRoleNameAndIsDeletedFalse(tenant, roleName.name()).isEmpty()) {
                Role role = new Role();
                role.setRoleName(roleName.name());
                role.setName(roleName.name());
                role.setStatus("ACTIVE");
                roleRepository.save(role);
            }
        }
        Currency uzs = currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(tenant, "UZS")
            .orElseGet(() -> currencyRepository.save(currency("UZS", "860", "so'm", true)));
        Currency usd = currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(tenant, "USD")
            .orElseGet(() -> currencyRepository.save(currency("USD", "840", "$", false)));
        if (exchangeRateRepository.findByTenantIdAndCompanyIdAndCurrency_IdAndRateDateAndSourceAndIsDeletedFalse(
                tenant, usd.getCompanyId(), usd.getId(), LocalDate.of(2020, 1, 1), "TEST_FIXTURE").isEmpty()) {
            ExchangeRate rate = new ExchangeRate();
            rate.setName("Reference USD rate");
            rate.setCode("USD-REFERENCE");
            rate.setStatus("ACTIVE");
            rate.setCurrency(usd);
            rate.setRateDate(LocalDate.of(2020, 1, 1));
            rate.setRateToBase(new BigDecimal("12650.000000"));
            rate.setSource("TEST_FIXTURE");
            exchangeRateRepository.save(rate);
        }
        for (String code : new String[] {"NON_MOVING_ACTION", "MATERIAL_TYPE"}) {
            if (classifierTypeRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenant, code).isEmpty()) {
                ClassifierType type = new ClassifierType();
                type.setCode(code);
                type.setName(code.replace('_', ' '));
                type.setStatus("ACTIVE");
                classifierTypeRepository.save(type);
            }
        }
        for (String code : new String[] {"COMPANY", "DIRECTORATE", "DEPARTMENT", "SECTION"}) {
            if (departmentTypeRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenant, code).isEmpty()) {
                DepartmentType type = new DepartmentType();
                type.setCode(code);
                type.setName(code);
                type.setStatus("ACTIVE");
                departmentTypeRepository.save(type);
            }
        }
    }

    private Currency currency(String code, String numericCode, String symbol, boolean base) {
        Currency currency = new Currency();
        currency.setCode(code);
        currency.setName(code);
        currency.setStatus("ACTIVE");
        currency.setIsoCode(code);
        currency.setNumericCode(numericCode);
        currency.setSymbol(symbol);
        currency.setBaseCurrency(base);
        return currency;
    }

    @Test
    void referenceDataSupportsRolesCurrenciesAndDictionaries() throws Exception {
        String tenantId = TenantContext.currentTenant();

        assertThat(Arrays.stream(RoleName.values())
            .allMatch(role -> roleRepository.findByTenantIdAndRoleNameAndIsDeletedFalse(tenantId, role.name()).isPresent()))
            .isTrue();

        Currency uzs = currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(tenantId, "UZS").orElseThrow();
        assertThat(uzs.isBaseCurrency()).isTrue();
        assertThat(uzs.getNumericCode()).isEqualTo("860");

        Currency usd = currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(tenantId, "USD").orElseThrow();
        assertThat(usd.getSymbol()).isEqualTo("$");

        String lookup = mockMvc.perform(get("/api/exchange-rates/lookup")
                .with(user("viewer").roles("VIEWER"))
                .param("currencyId", usd.getId().toString())
                .param("date", LocalDate.now().toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(objectMapper.readTree(lookup).path("data").decimalValue()).isEqualByComparingTo("12650.000000");

        assertThat(classifierTypeRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenantId, "NON_MOVING_ACTION")).isPresent();
        assertThat(classifierTypeRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenantId, "MATERIAL_TYPE")).isPresent();
        assertThat(departmentTypeRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenantId, "COMPANY")).isPresent();
        assertThat(departmentTypeRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenantId, "DEPARTMENT")).isPresent();
    }

    @Test
    void exchangeRateApiUpsertsAndLooksUpLatestHistoricalRate() throws Exception {
        Currency usd = currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(TenantContext.currentTenant(), "USD").orElseThrow();

        String payload = """
            {
              "currencyId": "%s",
              "rateDate": "2099-01-01",
              "rateToBase": 12777.12,
              "source": "MANUAL"
            }
            """.formatted(usd.getId());

        mockMvc.perform(post("/api/exchange-rates")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk());

        String updatePayload = """
            {
              "currencyId": "%s",
              "rateDate": "2099-01-01",
              "rateToBase": 12888.34,
              "source": "MANUAL"
            }
            """.formatted(usd.getId());

        mockMvc.perform(post("/api/exchange-rates")
                .with(user("manager").roles("MM_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload))
            .andExpect(status().isOk());

        String lookup = mockMvc.perform(get("/api/exchange-rates/lookup")
                .with(user("viewer").roles("VIEWER"))
                .param("currencyId", usd.getId().toString())
                .param("date", "2099-06-01"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(objectMapper.readTree(lookup).path("data").decimalValue()).isEqualByComparingTo("12888.340000");

        String history = mockMvc.perform(get("/api/exchange-rates")
                .with(user("viewer").roles("VIEWER"))
                .param("currencyId", usd.getId().toString())
                .param("query", "MANUAL")
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode content = objectMapper.readTree(history).path("data").path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("rateToBase").decimalValue()).isEqualByComparingTo("12888.340000");

        String baseLookup = mockMvc.perform(get("/api/exchange-rates/lookup")
                .with(user("viewer").roles("VIEWER"))
                .param("date", "2099-06-01"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(objectMapper.readTree(baseLookup).path("data").decimalValue()).isEqualByComparingTo(BigDecimal.ONE);

        String currencyJson = mockMvc.perform(get("/api/currencies/{id}", usd.getId())
                .with(user("viewer").roles("VIEWER")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode currency = objectMapper.readTree(currencyJson).path("data");
        assertThat(currency.has("exchangeRateToBase")).isFalse();
        assertThat(currency.path("rateDate").isNull()).isTrue();
    }

    @Test
    void classifierApiUsesClassifierTypeDictionary() throws Exception {
        String payload = """
            {
              "name": "Critical material",
              "classifierType": "MATERIAL_TYPE",
              "description": "ru: Критический материал\\nen: Critical material\\nuz: Muhim material"
            }
            """;

        mockMvc.perform(post("/api/classifiers")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated());

        String invalidPayload = """
            {
              "name": "Unknown",
              "classifierType": "UNKNOWN_TYPE"
            }
            """;

        mockMvc.perform(post("/api/classifiers")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload))
            .andExpect(status().isBadRequest());
    }

    @Test
    void departmentApiUsesDepartmentTypeDictionary() throws Exception {
        String payload = """
            {
              "name": "Temporary Section",
              "departmentType": "SECTION",
              "costCenter": "TMP",
              "sortOrder": 999
            }
            """;

        mockMvc.perform(post("/api/departments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated());

        String invalidPayload = """
            {
              "name": "Unknown Department Type",
              "departmentType": "UNKNOWN_TYPE"
            }
            """;

        mockMvc.perform(post("/api/departments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void departmentApiAllowsDuplicateNameWithinCompanyWithGeneratedCodes() throws Exception {
        String payload = """
            {
              "name": "Duplicate Department",
              "departmentType": "DEPARTMENT",
              "costCenter": "DUP"
            }
            """;

        String first = mockMvc.perform(post("/api/departments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String second = mockMvc.perform(post("/api/departments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode firstData = objectMapper.readTree(first).path("data");
        JsonNode secondData = objectMapper.readTree(second).path("data");
        assertThat(secondData.path("name").asText()).isEqualTo(firstData.path("name").asText());
        assertThat(secondData.path("code").asText()).isNotEqualTo(firstData.path("code").asText());
    }

    @Test
    void hrSyncUpsertsOrgDirectoriesAndBuildsHierarchy() throws Exception {
        String payload = """
            {
              "sourceSystem": "CORP_HR",
              "departments": [
                {"externalId": "ROOT-01", "name": "Corporate Center", "departmentType": "COMPANY", "costCenter": "ADM", "sortOrder": 1, "status": "ACTIVE"},
                {"externalId": "SUP-01", "parentExternalId": "ROOT-01", "name": "Supply Directorate", "departmentType": "DIRECTORATE", "costCenter": "SUP", "sortOrder": 2, "status": "ACTIVE"},
                {"externalId": "BUY-01", "parentExternalId": "SUP-01", "name": "Procurement Office", "departmentType": "DEPARTMENT", "costCenter": "PROC", "sortOrder": 3, "status": "ACTIVE"}
              ],
              "positions": [
                {"externalId": "POS-BUYER", "departmentExternalId": "BUY-01", "name": "Lead Buyer", "positionCategory": "SPECIALIST", "grade": "G3", "headcount": 2, "occupiedHeadcount": 1, "status": "ACTIVE"}
              ],
              "employees": [
                {"externalId": "EMP-MGR", "personnelNumber": "9001", "departmentExternalId": "SUP-01", "fullName": "Supply Manager", "email": "manager@example.com", "employmentStatus": "ACTIVE", "hireDate": "2024-01-15"},
                {"externalId": "EMP-BUYER", "personnelNumber": "9002", "departmentExternalId": "BUY-01", "positionExternalId": "POS-BUYER", "managerExternalId": "EMP-MGR", "fullName": "Lead Buyer Person", "email": "buyer@example.com", "employmentStatus": "ACTIVE", "hireDate": "2024-02-01"}
              ]
            }
            """;

        JsonNode sync = objectMapper.readTree(mockMvc.perform(post("/api/hr/sync")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()).path("data");

        assertThat(sync.path("departmentsUpserted").asInt()).isEqualTo(3);
        assertThat(sync.path("positionsUpserted").asInt()).isEqualTo(1);
        assertThat(sync.path("employeesUpserted").asInt()).isEqualTo(2);
        assertThat(sync.path("integrationEventId").asText()).isNotBlank();

        String tenantId = TenantContext.currentTenant();
        assertThat(departmentRepository.findByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, "BUY-01")).isPresent();
        assertThat(positionRepository.findByTenantIdAndExternalIdAndIsDeletedFalse(tenantId, "POS-BUYER")).isPresent();
        assertThat(employeeRepository.findByTenantIdAndPersonnelNumberAndIsDeletedFalse(tenantId, "9002")).isPresent();
        assertThat(integrationEventRepository.findAnyById(java.util.UUID.fromString(sync.path("integrationEventId").asText()))).isPresent();

        String tree = mockMvc.perform(get("/api/hr/org-tree").with(user("manager").roles("MM_MANAGER")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(tree).contains("Corporate Center", "Supply Directorate", "Procurement Office", "employeeCount", "positionCount");
    }
}

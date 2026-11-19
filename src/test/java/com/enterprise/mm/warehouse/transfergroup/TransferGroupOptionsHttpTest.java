package com.enterprise.mm.warehouse.transfergroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.AccessScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"app.seed.enabled=false", "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TransferGroupOptionsHttpTest {
    static final String PATH = "/api/v1/warehouse/transfer-groups";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WarehouseTransferGroupRepository groups;
    @Autowired WarehouseTransferGroupItemRepository items;
    @Autowired WarehouseRepository warehouses;
    @Autowired EmployeeRepository employees;
    String tenant = "group-http";
    UUID company = UUID.randomUUID();
    Warehouse source;
    Warehouse destination;
    WarehouseTransferGroup group;
    String reader;

    @BeforeEach void fixture() {
        TenantContext.setTenant(tenant); CompanyContext.setCompany(company);
        source = warehouse("Source supplies"); destination = warehouse("Destination supplies");
        group = new WarehouseTransferGroup();
        group.setCode("WTG-42"); group.setName("ru: Реактивы\nen: Reagents\nuz: Reagentlar");
        group.setSourceWarehouseId(source.getId()); group.setStatus("CLOSED");
        group = groups.saveAndFlush(group);
        reader = "reader-" + UUID.randomUUID();
        assign(reader, source);
    }
    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_KEEPER"})
    void allowedSourceRolesGetMinimalLocalizedOptionsAndCanReadSameGroup(String role) throws Exception {
        String body = mvc.perform(request(PATH).with(user(reader).roles(role)).header("Accept-Language", "uz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.totalPages").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(group.getId().toString()))
            .andExpect(jsonPath("$.data.content[0].code").value("WTG-42"))
            .andExpect(jsonPath("$.data.content[0].name").value("Reagentlar"))
            .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).at("/data/content/0").size()).isEqualTo(3);
        mvc.perform(request(PATH + "/" + group.getId()).with(user(reader).roles(role)))
            .andExpect(status().isOk());
        mvc.perform(request(PATH + "/" + group.getId() + "/progress").with(user(reader).roles(role)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.plannedQuantity").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_HEAD", "AUDITOR", "VIEWER", "DEPARTMENT_WORKER", "PROCUREMENT_MANAGER"})
    void unrelatedRolesCannotReadAnyGroupEndpoint(String role) throws Exception {
        for (String path : List.of(PATH, PATH + "/" + group.getId(), PATH + "/" + group.getId() + "/progress")) {
            mvc.perform(request(path).with(user(reader).roles(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test void destinationMembershipDoesNotGrantWholeGroupAccess() throws Exception {
        String recipient = "recipient-" + UUID.randomUUID();
        assign(recipient, destination);
        var item = new WarehouseTransferGroupItem(); item.setName("Reagents");
        item.setGroupId(group.getId()); item.setDestinationWarehouseId(destination.getId());
        item.setMaterialId(UUID.randomUUID()); items.saveAndFlush(item);
        mvc.perform(request(PATH).with(user(recipient).roles("WAREHOUSE_KEEPER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty())
            .andExpect(jsonPath("$.data.totalElements").value(0));
        for (String path : List.of(PATH + "/" + group.getId(), PATH + "/" + group.getId() + "/progress")) {
            mvc.perform(request(path).with(user(recipient).roles("WAREHOUSE_KEEPER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NotFoundException"));
        }
        mvc.perform(request(PATH).with(user(recipient).roles("WAREHOUSE_KEEPER"))
                .param("sourceWarehouseId", source.getId().toString()))
            .andExpect(status().isNotFound());
    }

    @Test void unassignedWarehouseActorGets404AndCannotReadByKnownId() throws Exception {
        mvc.perform(request(PATH).with(user("unassigned").roles("WAREHOUSE_OPERATOR")))
            .andExpect(status().isNotFound());
        mvc.perform(request(PATH + "/" + group.getId() + "/progress")
                .with(user("unassigned").roles("WAREHOUSE_OPERATOR")))
            .andExpect(status().isNotFound());
    }

    @Test void requiresValidAuthentication() throws Exception {
        mvc.perform(request(PATH)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(request(PATH).header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,invalid", "size,0", "size,101", "sourceWarehouseId,invalid", "companyScope,ALL"})
    void rejectsInvalidRequestParameters(String parameter, String value) throws Exception {
        mvc.perform(request(PATH).with(user(reader).roles("ADMIN")).param(parameter, value))
            .andExpect(status().isBadRequest());
    }

    @Test void searchLimitsAndSourceFilterUseExistingErrorEnvelope() throws Exception {
        mvc.perform(request(PATH).with(user(reader).roles("ADMIN")).header("Accept-Language", "uz")
                .param("search", "x".repeat(201)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("BadRequestException"))
            .andExpect(jsonPath("$.message").value("Qidiruv matni 200 belgidan oshmasligi kerak"));
        mvc.perform(request(PATH).with(user(reader).roles("ADMIN"))
                .param("sourceWarehouseId", source.getId().toString()).param("search", "  wTg-42  "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(request(PATH).with(user(reader).roles("ADMIN")).param("search", "   "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(request(PATH).with(user(reader).roles("ADMIN"))
                .param("sourceWarehouseId", UUID.randomUUID().toString()))
            .andExpect(status().isNotFound());
    }

    @Test void companyAndTenantIsolationAlsoAppliesToGlobalAdminAndDirectProgressReads() throws Exception {
        var global = new UsernamePasswordAuthenticationToken("global-admin", "unused",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        global.setDetails(new AccessScope.RequestScope(true));
        for (String scope : List.of("", "CURRENT_COMPANY")) {
            var request = request(PATH).with(authentication(global));
            if (!scope.isEmpty()) request.param("companyScope", scope);
            mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        }
        mvc.perform(get(PATH).with(user(reader).roles("ADMIN"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", UUID.randomUUID()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get(PATH + "/" + group.getId() + "/progress").with(user(reader).roles("ADMIN"))
                .header("X-Tenant-Id", "another-tenant").header("X-Company-Id", company))
            .andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + group.getId() + "/progress").with(user(reader).roles("ADMIN"))
                .header("X-Tenant-Id", tenant).header("X-Company-Id", UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test void openApiExposesListOperationAndOptionSchema() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var spec = json.readTree(body);
        assertThat(spec.path("paths").path(PATH).has("get")).isTrue();
        assertThat(spec.at("/components/schemas/TransferGroupOptionResponse/properties").size()).isEqualTo(3);
    }

    private MockHttpServletRequestBuilder request(String path) {
        return get(path).header("X-Tenant-Id", tenant).header("X-Company-Id", company);
    }
    private Warehouse warehouse(String name) {
        var warehouse = new Warehouse(); warehouse.setName(name);
        return warehouses.saveAndFlush(warehouse);
    }
    private void assign(String username, Warehouse warehouse) {
        var employee = new Employee(); employee.setName("Warehouse employee"); employee.setPersonnelNumber(username);
        employee.setTenantId(tenant); employee.setCompanyId(company); employee.setWarehouse(warehouse);
        employees.saveAndFlush(employee);
    }
}

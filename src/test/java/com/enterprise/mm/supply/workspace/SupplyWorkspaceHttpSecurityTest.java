package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.supply.workspace.controller.SupplyWorkspaceController;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKey;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.service.SupplyCalendarService;
import com.enterprise.mm.supply.workspace.service.SupplyCommitmentContextService;
import com.enterprise.mm.supply.workspace.service.SupplyPerformanceService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkAssignmentService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceQueryService;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceSelectorService;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:supply_http_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class SupplyWorkspaceHttpSecurityTest {
    private static final UUID COMPANY_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID COMPANY_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final String TENANT = "tenant-security";

    @Autowired MockMvc mvc;
    @MockBean SupplyWorkspaceQueryService workspaceService;
    @MockBean SupplyCalendarService calendarService;
    @MockBean SupplyCommitmentContextService contextService;
    @MockBean SupplyPerformanceService performanceService;
    @MockBean SupplyWorkAssignmentService assignmentService;
    @MockBean SupplyWorkspaceSelectorService selectorService;

    @Test
    void unauthenticatedSupplyReadReturnsJson401() throws Exception {
        mvc.perform(get("/api/workspaces/supply/overview")
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.errorCode").isString());
    }

    @Test
    void viewerCanReadWorkspaceAndSafeSelectors() throws Exception {
        mvc.perform(get("/api/workspaces/supply/overview")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A))
            .andExpect(status().isOk());
        mvc.perform(get("/api/workspaces/supply/selectors/companies")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A))
            .andExpect(status().isOk());
        mvc.perform(get("/api/workspaces/supply/performance")
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A))
            .andExpect(status().isOk());
        mvc.perform(get("/api/workspaces/supply/commitments/{key}/context", encodedKey())
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A))
            .andExpect(status().isOk());
    }

    @Test
    void accountantCanReadSupplyCalendar() throws Exception {
        mvc.perform(get("/api/workspaces/supply/calendar")
                .with(user("accountant").roles("ACCOUNTANT"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .param("from", "2026-07-01")
                .param("to", "2026-07-08"))
            .andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("mutationRoutes")
    void viewerCannotMutateAssignments(String route, String body) throws Exception {
        mvc.perform(post(route, encodedKey())
                .with(user("viewer").roles("VIEWER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void specialistCanClaimButCannotAssignOrEscalate() throws Exception {
        mvc.perform(post("/api/workspaces/supply/commitments/{key}/claim", encodedKey())
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .contentType(APPLICATION_JSON)
                .content("{\"reason\":\"taking ownership\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/workspaces/supply/commitments/{key}/assign", encodedKey())
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .contentType(APPLICATION_JSON)
                .content(assignBody()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/workspaces/supply/commitments/{key}/escalate", encodedKey())
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .contentType(APPLICATION_JSON)
                .content(escalateBody()))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("managerMutationRoles")
    void managersCanAssignAndEscalate(String role) throws Exception {
        mvc.perform(post("/api/workspaces/supply/commitments/{key}/assign", encodedKey())
                .with(user(role.toLowerCase()).roles(role))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .contentType(APPLICATION_JSON)
                .content(assignBody()))
            .andExpect(status().isOk());
        mvc.perform(post("/api/workspaces/supply/commitments/{key}/escalate", encodedKey())
                .with(user(role.toLowerCase()).roles(role))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .contentType(APPLICATION_JSON)
                .content(escalateBody()))
            .andExpect(status().isOk());
    }

    @Test
    void specialistCannotRequestManagementLens() throws Exception {
        mvc.perform(get("/api/workspaces/supply/overview")
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .param("lens", "MANAGEMENT"))
            .andExpect(status().isForbidden());
    }

    @Test
    void warehouseKeeperIsBoundToWarehouseScopedCalendarContract() throws Exception {
        mvc.perform(get("/api/workspaces/supply/calendar")
                .with(user("keeper").roles("WAREHOUSE_KEEPER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .param("from", "2026-07-01")
                .param("to", "2026-07-08")
                .param("sources", "PAYMENT"))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<SupplyWorkspaceQuery> query =
            org.mockito.ArgumentCaptor.forClass(SupplyWorkspaceQuery.class);
        verify(calendarService).calendar(query.capture(), any());
        assertThat(query.getValue().warehouseScoped()).isTrue();
        assertThat(query.getValue().authorizedWarehouseIds()).isEmpty();
    }

    @Test
    void authenticatedUserCannotWidenCompanyScope() throws Exception {
        mvc.perform(get("/api/workspaces/supply/overview")
                .with(user("manager").roles("MM_MANAGER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A)
                .param("companyId", COMPANY_B.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("SUPPLY_COMPANY_FORBIDDEN"));
    }

    @Test
    void unsupportedSupplyMethodsAreDeniedEvenToManagers() throws Exception {
        mvc.perform(delete("/api/workspaces/supply/commitments/{key}", encodedKey())
                .with(user("manager").roles("MM_MANAGER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", COMPANY_A))
            .andExpect(status().isForbidden());
    }

    @Test
    void routeAndMethodAuthorizationAreBothExplicit() throws Exception {
        String security = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/security/SecurityConfig.java"));
        assertThat(security).contains(
            "SUPPLY_WORKSPACE_READ_ROLES",
            "SUPPLY_WORKSPACE_MANAGER_ROLES",
            "\"/api/workspaces/supply/**\"");
        assertThat(security).contains(
            ".requestMatchers(HttpMethod.POST, SUPPLY_WORKSPACE_CLAIM).hasRole(\"PROCUREMENT_SPECIALIST\")",
            ".requestMatchers(HttpMethod.POST, SUPPLY_WORKSPACE_MANAGER_MUTATIONS).hasAnyRole(SUPPLY_WORKSPACE_MANAGER_ROLES)",
            ".requestMatchers(HttpMethod.GET, \"/api/workspaces/supply/**\").hasAnyRole(SUPPLY_WORKSPACE_READ_ROLES)",
            ".requestMatchers(\"/api/workspaces/supply/**\").denyAll()");

        PreAuthorize classRule = AnnotatedElementUtils.findMergedAnnotation(
            SupplyWorkspaceController.class, PreAuthorize.class);
        assertThat(classRule).isNotNull();
        assertThat(classRule.value()).isEqualTo(SupplyWorkspaceController.READ_AUTHORIZATION);
        assertMethodRule("claim", SupplyWorkspaceController.SPECIALIST_AUTHORIZATION);
        assertMethodRule("assign", SupplyWorkspaceController.MANAGER_AUTHORIZATION);
        assertMethodRule("escalate", SupplyWorkspaceController.MANAGER_AUTHORIZATION);
    }

    private void assertMethodRule(String name, String expected) {
        Method method = java.util.Arrays.stream(SupplyWorkspaceController.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow();
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        assertThat(rule).isNotNull();
        assertThat(rule.value()).isEqualTo(expected);
    }

    static List<Arguments> mutationRoutes() {
        return List.of(
            Arguments.of("/api/workspaces/supply/commitments/{key}/assign", assignBody()),
            Arguments.of("/api/workspaces/supply/commitments/{key}/claim", "{\"reason\":\"claim\"}"),
            Arguments.of("/api/workspaces/supply/commitments/{key}/escalate", escalateBody()));
    }

    static List<String> managerMutationRoles() {
        return List.of("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER");
    }

    private static String assignBody() {
        return "{\"ownerUserId\":\"20000000-0000-0000-0000-000000000001\","
            + "\"reason\":\"manager assignment\"}";
    }

    private static String escalateBody() {
        return "{\"severity\":\"BLOCKING\",\"reason\":\"management escalation\","
            + "\"expectedVersion\":1}";
    }

    private static String encodedKey() {
        return new SupplyCommitmentKeyCodec().encode(new SupplyCommitmentKey(
            SupplyCommitmentKind.PO,
            UUID.fromString("30000000-0000-0000-0000-000000000001")));
    }
}

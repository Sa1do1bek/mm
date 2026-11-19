package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.controller.ManufacturerController;
import com.enterprise.mm.controller.QmsAssessmentController;
import com.enterprise.mm.controller.QmsCriteriaController;
import com.enterprise.mm.controller.masterdata.ChartOfAccountController;
import com.enterprise.mm.controller.masterdata.ReferenceMappingController;
import com.enterprise.mm.payload.QmsAssessmentApproveRequest;
import com.enterprise.mm.service.ServiceAcceptanceWorkspaceService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class SupplierNsiHttpSecurityTest {
    private static final String REFERENCE_MUTATION_ROLES = "hasAnyRole('ADMIN','MM_MANAGER')";
    private static final String CHART_MUTATION_ROLES = "hasAnyRole('ADMIN','FINANCE_CONTROLLER')";
    private static final String QMS_MUTATION_ROLES = "hasAnyRole('ADMIN','MM_MANAGER','SUPPLIER_MANAGER','PROCUREMENT_MANAGER')";

    @Autowired MockMvc mvc;
    @Autowired ManufacturerController manufacturerController;
    @Autowired ReferenceMappingController referenceMappingController;
    @Autowired ChartOfAccountController chartOfAccountController;
    @Autowired QmsAssessmentController qmsAssessmentController;
    @Autowired QmsCriteriaController qmsCriteriaController;
    @MockBean ServiceAcceptanceWorkspaceService serviceAcceptanceWorkspaceService;

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotApproveQmsAssessment() throws Exception {
        mvc.perform(post("/api/qms/assessments/{id}/approve", UUID.randomUUID()))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DEPARTMENT_WORKER")
    void departmentWorkerCannotMutateReferenceMapping() throws Exception {
        mvc.perform(post("/api/reference-mappings").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotMutateManufacturer() throws Exception {
        mvc.perform(post("/api/manufacturers").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MM_MANAGER")
    void mmManagerCannotMutateChartOfAccounts() throws Exception {
        mvc.perform(post("/api/chart-of-accounts").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotMutateQmsCriteria() throws Exception {
        mvc.perform(post("/api/v1/qms/criteria").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/manufacturers",
        "/api/reference-mappings",
        "/api/chart-of-accounts",
        "/api/qms/assessments",
        "/api/v1/qms/criteria"
    })
    void viewerCanReadSupplierNsiReferences(String endpoint) throws Exception {
        mvc.perform(get(endpoint).with(user("viewer").roles("VIEWER")))
            .andExpect(status().isOk());
    }

    @Test
    void mmManagerCanCreateManufacturer() throws Exception {
        mvc.perform(post("/api/manufacturers")
                .with(user("mm-manager").roles("MM_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated());
    }

    @Test
    void mmManagerCanReachReferenceMappingMutationValidation() throws Exception {
        mvc.perform(post("/api/reference-mappings")
                .with(user("mm-manager").roles("MM_MANAGER"))
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void financeControllerCanReachChartMutationValidation() throws Exception {
        mvc.perform(post("/api/chart-of-accounts")
                .with(user("finance-controller").roles("FINANCE_CONTROLLER"))
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MM_MANAGER", "SUPPLIER_MANAGER", "PROCUREMENT_MANAGER"})
    void configuredQmsMutationRolesReachCriteriaValidation(String role) throws Exception {
        mvc.perform(post("/api/v1/qms/criteria")
                .with(user(role.toLowerCase()).roles(role))
                .contentType(APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void manufacturerControllerProxyDeniesViewerBeforeMethodInvocation() {
        assertThatThrownBy(() -> manufacturerController.create(null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "DEPARTMENT_WORKER")
    void referenceMappingControllerProxyDeniesDepartmentWorkerBeforeMethodInvocation() {
        assertThatThrownBy(() -> referenceMappingController.generateDefaults("ONE_C", "MANAGEMENT"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "MM_MANAGER")
    void chartControllerProxyDeniesMmManagerBeforeMethodInvocation() {
        assertThatThrownBy(() -> chartOfAccountController.create(null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void qmsAssessmentControllerProxyDeniesViewerBeforeMethodInvocation() {
        assertThatThrownBy(() -> qmsAssessmentController.approve(
            UUID.randomUUID(),
            "security-boundary-test",
            new QmsAssessmentApproveRequest(0L)
        ))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void qmsCriteriaControllerProxyDeniesViewerBeforeMethodInvocation() {
        assertThatThrownBy(() -> qmsCriteriaController.create(null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void everySupplierNsiMutationHandlerDeclaresTheIntendedMethodAuthorization() {
        Map.of(
            ManufacturerController.class, REFERENCE_MUTATION_ROLES,
            ReferenceMappingController.class, REFERENCE_MUTATION_ROLES,
            ChartOfAccountController.class, CHART_MUTATION_ROLES,
            QmsAssessmentController.class, QMS_MUTATION_ROLES,
            QmsCriteriaController.class, QMS_MUTATION_ROLES
        ).forEach((controllerType, expectedExpression) -> {
            var mutationHandlers = Arrays.stream(controllerType.getDeclaredMethods())
                .filter(this::isMutationHandler)
                .toList();

            assertThat(mutationHandlers)
                .as("%s mutation handlers", controllerType.getSimpleName())
                .isNotEmpty()
                .allSatisfy(method -> {
                    PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                    assertThat(annotation)
                        .as("%s#%s method authorization", controllerType.getSimpleName(), method.getName())
                        .isNotNull();
                    assertThat(annotation.value())
                        .as("%s#%s role expression", controllerType.getSimpleName(), method.getName())
                        .isEqualTo(expectedExpression);
                });
        });
    }

    private boolean isMutationHandler(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        return mapping != null && Arrays.stream(mapping.method()).anyMatch(this::isMutationMethod);
    }

    private boolean isMutationMethod(RequestMethod method) {
        return switch (method) {
            case POST, PUT, PATCH, DELETE -> true;
            default -> false;
        };
    }
}

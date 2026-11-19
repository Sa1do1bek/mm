package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.controller.ApprovalFlowController;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowRequest;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApprovalFlowRequestTypeTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ApprovalFlowRepository flows = mock(ApprovalFlowRepository.class);
    private final ApprovalModuleRegistry registry = mock(ApprovalModuleRegistry.class);
    private final ApprovalModuleAdapter adapter = mock(ApprovalModuleAdapter.class);
    private final ApprovalFlowAdminService service = new ApprovalFlowAdminService(
        flows, mock(ApprovalFlowStepRepository.class), mock(ApprovalFlowParallelAssigneeRepository.class),
        registry, mock(AuditService.class), mock(UserRepository.class), mock(DepartmentRepository.class));
    private ApprovalFlow saved;

    @BeforeEach
    void setup() {
        TenantContext.setTenant("approval-both-test");
        CompanyContext.setCompany(UUID.randomUUID());
        when(registry.adapter("PURCHASE_REQUEST")).thenReturn(adapter);
        when(adapter.usesRequestType()).thenReturn(true);
        when(flows.save(any(ApprovalFlow.class))).thenAnswer(call -> {
            saved = call.getArgument(0);
            if (saved.getId() == null) saved.setId(UUID.randomUUID());
            saved.setTenantId(TenantContext.currentTenant());
            saved.setCompanyId(CompanyContext.currentCompany());
            return saved;
        });
        when(flows.findByIdAndIsDeletedFalse(any())).thenAnswer(call -> Optional.ofNullable(saved));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createsAndReturnsBothWithoutChangingTheWireFieldName() throws Exception {
        var request = json.readValue("""
            {"name":"Shared approval", "moduleKey":"PURCHASE_REQUEST", "requestType":"BOTH"}
            """, ApprovalFlowRequest.class);
        var response = service.create(request);
        assertThat(json.valueToTree(response).path("requestType").asText()).isEqualTo("BOTH");
        assertThat(json.valueToTree(service.get(response.id())).path("requestType").asText()).isEqualTo("BOTH");
        assertThat(saved.getRequestType().name()).isEqualTo("BOTH");
    }

    @Test
    void updatesFromGoodsToBothAndThenService() throws Exception {
        var response = service.create(request("GOODS"));
        assertThat(response.requestType().name()).isEqualTo("GOODS");
        assertThat(service.update(response.id(), request("BOTH")).requestType().name()).isEqualTo("BOTH");
        assertThat(service.update(response.id(), request("SERVICE")).requestType().name()).isEqualTo("SERVICE");
    }

    @Test
    void modulesWithoutRequestTypesStillUseNull() throws Exception {
        when(adapter.usesRequestType()).thenReturn(false);
        assertThat(service.create(request("BOTH")).requestType()).isNull();
        assertThat(saved.getRequestType()).isNull();
    }

    @Test
    void legacyNullRequestRemainsCompatible() throws Exception {
        var request = json.readValue("""
            {"name":"Legacy shared approval", "moduleKey":"PURCHASE_REQUEST"}
            """, ApprovalFlowRequest.class);
        assertThat(service.create(request).requestType()).isNull();
    }

    @Test
    void purchaseDocumentEnumStillRejectsBoth() {
        assertThatThrownBy(() -> json.readValue("\"BOTH\"", com.enterprise.mm.enums.RequestType.class))
            .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }

    @Test
    void httpApiAcceptsBothAndReturnsItInDetailsAndFilteredList() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new ApprovalFlowController(service)).build();
        mvc.perform(post("/api/approval-flows")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Shared approval", "moduleKey":"PURCHASE_REQUEST", "requestType":"BOTH"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.requestType").value("BOTH"));
        mvc.perform(get("/api/approval-flows/" + saved.getId()).accept(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requestType").value("BOTH"));
        when(flows.search(any(), any(), any(), any(), eq(List.of(ApprovalRequestType.BOTH)), eq(false),
            any(), anyBoolean(), any(), any(), anyBoolean(), any(), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(saved)));
        mvc.perform(get("/api/approval-flows").param("requestType", "BOTH")
                .accept(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].requestType").value("BOTH"));
    }

    @Test
    void editResponseKeepsAllTranslationsWhileNameFollowsRequestedLanguage() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new ApprovalFlowController(service))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json))
            .setControllerAdvice(new com.enterprise.mm.web.LocalizedNameResponseAdvice(json)).build();
        var body = json.createObjectNode().put("name", "uz: Tasdiqlash\nru: Согласование\nen: Approval")
            .put("moduleKey", "PURCHASE_REQUEST").put("requestType", "BOTH");
        mvc.perform(post("/api/approval-flows").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Accept-Language", "en").content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.nameUz").value("Tasdiqlash"));
        when(flows.search(any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(),
            any(), any(), anyBoolean(), any(), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(saved)));
        for (var entry : java.util.Map.of("uz", "Tasdiqlash", "ru", "Согласование", "en", "Approval").entrySet()) {
            for (String path : List.of("/api/approval-flows/" + saved.getId(), "/api/approval-flows")) {
                String root = path.endsWith(saved.getId().toString()) ? "$.data" : "$.data.content[0]";
                mvc.perform(get(path).header("Accept-Language", entry.getKey()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(root + ".name").value(entry.getValue()))
                    .andExpect(jsonPath(root + ".nameUz").value("Tasdiqlash"))
                    .andExpect(jsonPath(root + ".nameRu").value("Согласование"))
                    .andExpect(jsonPath(root + ".nameEn").value("Approval"));
            }
        }
        body.put("name", "uz: Yangi\nru: Новый\nen: New");
        mvc.perform(put("/api/approval-flows/" + saved.getId())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Accept-Language", "ru").content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Новый"))
            .andExpect(jsonPath("$.data.nameUz").value("Yangi"))
            .andExpect(jsonPath("$.data.nameRu").value("Новый"))
            .andExpect(jsonPath("$.data.nameEn").value("New"));
    }

    @Test
    void legacyPlainNameRemainsAvailableInEveryEditField() throws Exception {
        service.create(request("GOODS"));
        saved.setName("Legacy approval");
        var response = json.valueToTree(service.get(saved.getId()));
        for (String field : List.of("name", "nameUz", "nameRu", "nameEn")) {
            assertThat(response.path(field).asText()).isEqualTo("Legacy approval");
        }
    }

    private ApprovalFlowRequest request(String type) throws Exception {
        return json.readValue("""
            {"name":"Shared approval", "moduleKey":"PURCHASE_REQUEST", "requestType":"%s"}
            """.formatted(type), ApprovalFlowRequest.class);
    }
}

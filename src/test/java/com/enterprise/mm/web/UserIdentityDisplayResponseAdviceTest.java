package com.enterprise.mm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class UserIdentityDisplayResponseAdviceTest {
    private static final String TENANT = "tenant-identity-display";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void replacesUserReferenceFieldsWithFullNamesAcrossNestedResponsePayloads() {
        givenUser("creator.one", "Creator One");
        givenUser("buyer.two", "Buyer Two");
        givenUser("approver.three", "Approver Three");
        givenUser("actor.four", "Actor Four");
        givenUser("audit.user", "Audit User");

        var body = ApiResponse.ok(Map.of(
            "createdBy", "creator.one",
            "nested", List.of(Map.of(
                "ownerUsername", "buyer.two",
                "assigneeUsername", "approver.three",
                "actedBy", "actor.four"
            )),
            "audit", Map.of(
                "action", "UPDATED",
                "username", "audit.user",
                "occurredAt", "2026-08-11T10:00:00"
            )
        ));

        Object result = advice().beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            null,
            null
        );

        JsonNode tree = (JsonNode) result;
        JsonNode data = tree.path("data");
        assertThat(data.path("createdBy").asText()).isEqualTo("Creator One");
        assertThat(data.path("nested").get(0).path("ownerUsername").asText()).isEqualTo("Buyer Two");
        assertThat(data.path("nested").get(0).path("assigneeUsername").asText()).isEqualTo("Approver Three");
        assertThat(data.path("nested").get(0).path("actedBy").asText()).isEqualTo("Actor Four");
        assertThat(data.path("audit").path("username").asText()).isEqualTo("Audit User");
    }

    @Test
    void keepsCredentialUsernameFieldsAsAccountIdentifiers() {
        givenUser("admin", "System Administrator");
        givenUser("integration-login", "Integration User");

        var body = ApiResponse.ok(Map.of(
            "login", Map.of(
                "token", "jwt-token",
                "username", "admin"
            ),
            "integration", Map.of(
                "authType", "BASIC",
                "username", "integration-login",
                "passwordConfigured", true
            )
        ));

        Object result = advice().beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            null,
            null
        );

        JsonNode data = ((JsonNode) result).path("data");
        assertThat(data.path("login").path("username").asText()).isEqualTo("admin");
        assertThat(data.path("integration").path("username").asText()).isEqualTo("integration-login");
    }

    @Test
    void replacesRfqWorkspaceUserFieldsWithFullNames() {
        givenUser("buyer.one", "Buyer One");
        givenUser("requester.two", "Requester Two");
        givenUser("recorder.three", "Recorder Three");
        givenUser("audit.four", "Audit Four");

        var body = ApiResponse.ok(Map.of(
            "content", List.of(Map.of(
                "code", "RFQ-001",
                "responsibleBuyer", "buyer.one"
            )),
            "detail", Map.of(
                "overview", Map.of(
                    "rfqId", "8e53a97f-d411-43f4-857a-bf8214b06e07",
                    "responsibleBuyer", "buyer.one"
                ),
                "purchaseRequests", List.of(Map.of(
                    "code", "PR-001",
                    "requestedBy", "requester.two"
                )),
                "proposals", List.of(Map.of(
                    "current", Map.of(
                        "recordedBy", "recorder.three"
                    )
                )),
                "audit", List.of(Map.of(
                    "action", "RFQ_PUBLISHED",
                    "username", "audit.four",
                    "occurredAt", "2026-08-11T10:00:00"
                ))
            )
        ));

        Object result = advice().beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            null,
            null
        );

        JsonNode data = ((JsonNode) result).path("data");
        assertThat(data.path("content").get(0).path("responsibleBuyer").asText()).isEqualTo("Buyer One");
        assertThat(data.path("detail").path("overview").path("responsibleBuyer").asText()).isEqualTo("Buyer One");
        assertThat(data.path("detail").path("purchaseRequests").get(0).path("requestedBy").asText())
            .isEqualTo("Requester Two");
        assertThat(data.path("detail").path("proposals").get(0).path("current").path("recordedBy").asText())
            .isEqualTo("Recorder Three");
        assertThat(data.path("detail").path("audit").get(0).path("username").asText()).isEqualTo("Audit Four");
    }

    private UserIdentityDisplayResponseAdvice advice() {
        return new UserIdentityDisplayResponseAdvice(
            objectMapper,
            new UserIdentityDisplayEnricher(userRepository)
        );
    }

    private void givenUser(String username, String fullName) {
        User user = new User();
        user.setUsername(username);
        user.setFullName(fullName);
        when(userRepository.resolveActiveUserByUsername(TENANT, CompanyContext.DEFAULT_COMPANY_ID, username))
            .thenReturn(Optional.of(user));
    }
}

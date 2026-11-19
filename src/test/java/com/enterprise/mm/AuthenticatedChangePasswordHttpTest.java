package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.payload.ChangePasswordRequest;
import com.enterprise.mm.service.PasswordChangeService;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class AuthenticatedChangePasswordHttpTest {
    @Autowired private MockMvc mvc;
    @MockBean private PasswordChangeService passwordChangeService;

    @Test
    void authenticatedRequestBindsExactFieldsAndReturnsNoContent() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                .with(user("self-service-user").roles("VIEWER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "oldPassword": " current-password ",
                      "newPassword": " new-password-value ",
                      "confirmNewPassword": " new-password-value "
                    }
                    """))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        ArgumentCaptor<ChangePasswordRequest> request =
            ArgumentCaptor.forClass(ChangePasswordRequest.class);
        verify(passwordChangeService).changePassword(any(Authentication.class), request.capture());
        assertThat(request.getValue().getOldPassword()).isEqualTo(" current-password ");
        assertThat(request.getValue().getNewPassword()).isEqualTo(" new-password-value ");
        assertThat(request.getValue().getConfirmNewPassword()).isEqualTo(" new-password-value ");
    }

    @Test
    void unauthenticatedRequestReturnsStableJsonError() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "oldPassword": "current-password",
                      "newPassword": "new-password-value",
                      "confirmNewPassword": "new-password-value"
                    }
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.data").value(nullValue()))
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void oversizedOldPasswordIsRejectedBeforePasswordHashWork() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                .with(user("self-service-user").roles("VIEWER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "oldPassword": "%s",
                      "newPassword": "new-password-value",
                      "confirmNewPassword": "new-password-value"
                    }
                    """.formatted("x".repeat(257))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void allPasswordFieldsAreRequired() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                .with(user("self-service-user").roles("VIEWER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void dtoContainsNoUserIdOrCurrentPasswordAlias() {
        Set<String> fields = Arrays.stream(ChangePasswordRequest.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder(
            "oldPassword",
            "newPassword",
            "confirmNewPassword"
        );
        assertThat(new ChangePasswordRequest("old", "new", "new").toString())
            .doesNotContain("old", "new");
    }
}

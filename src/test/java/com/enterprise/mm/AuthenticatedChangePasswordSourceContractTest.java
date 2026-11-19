package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthenticatedChangePasswordSourceContractTest {
    @Test
    void endpointIsExplicitlyAuthenticatedBeforeTheAuthWildcard() throws Exception {
        String security = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/security/SecurityConfig.java"
        ));
        int protectedMatcher = security.indexOf(
            "requestMatchers(HttpMethod.POST, \"/api/auth/change-password\").authenticated()"
        );
        int publicWildcard = security.indexOf("\"/api/auth/**\"");

        assertThat(protectedMatcher).isGreaterThanOrEqualTo(0);
        assertThat(publicWildcard).isGreaterThan(protectedMatcher);
    }

    @Test
    void requestDtoUsesOnlyTheApprovedSecretFieldNamesAndRedactsToString() throws Exception {
        String dto = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/payload/ChangePasswordRequest.java"
        ));

        assertThat(dto).contains("oldPassword", "newPassword", "confirmNewPassword", "[REDACTED]");
        assertThat(dto).doesNotContain("currentPassword", "@Data");
    }
}

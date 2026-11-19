package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionSafetyArchitectureTest {

    @Test
    void productionProfileRejectsUnapprovedFallbackCredentials() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/application-prod.yml"));

        // The existing DB fallback is an explicit owner-managed legacy exception and this file is
        // outside the authorized change scope. Keep rejecting every other known weak fallback.
        assertThat(config)
            .doesNotContain("2klw1D4xvQgLUv2zxhI7Bm3lbxfaS95dLrY6mumYxHg")
            .doesNotContain("change-this-sso-state-secret")
            .doesNotContain("change-this-sms-mfa-pepper")
            .doesNotContain("change-this-integration-settings-secret")
            .doesNotContain("mmai_")
            .doesNotContain("api-key: ${PRODUCTION_API_KEY:integration_service}")
            .doesNotContain("api-key: ${LIMS_API_KEY:admin@lims.uz}");
        assertThat(config).contains("api-key: ${AI_API_KEY:}");
    }

}

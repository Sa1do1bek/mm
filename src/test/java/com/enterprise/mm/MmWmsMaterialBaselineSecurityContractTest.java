package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MmWmsMaterialBaselineSecurityContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void baselineReplayIsExplicitlyAllowedForTheDedicatedOperatorRoleOnly() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/MmWmsMaterialBaselineController.java"));
        String security = Files.readString(ROOT.resolve("security/SecurityConfig.java"));

        assertThat(controller).contains(
            "@PreAuthorize(\"hasAnyRole('ADMIN','MM_MANAGER','INTEGRATION_OPERATOR')\")"
        );
        assertThat(security).contains(
            ".requestMatchers(HttpMethod.POST, \"/api/integrations/wms/material-baseline\")",
            "\"/api/integrations/wms/material-snapshots\"",
            ".hasAnyRole(\"ADMIN\", \"MM_MANAGER\", \"INTEGRATION_OPERATOR\")"
        );
    }
}

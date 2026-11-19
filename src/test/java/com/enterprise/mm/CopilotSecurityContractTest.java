package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CopilotSecurityContractTest {
    @Test
    void copilotProxyIsAuthenticatedAndAllowedBeforeFallback() throws Exception {
        String security = Files.readString(Path.of("src/main/java/com/enterprise/mm/security/SecurityConfig.java"));
        String controller = Files.readString(Path.of("src/main/java/com/enterprise/mm/controller/CopilotController.java"));
        String client = Files.readString(Path.of("src/main/java/com/enterprise/mm/service/HttpMmAiClient.java"));

        int copilotRule = security.indexOf(".requestMatchers(AI_COPILOT).authenticated()");
        int fallbackRule = security.indexOf(".anyRequest().authenticated()");

        assertThat(security).contains("private static final String[] AI_COPILOT = {\"/api/mm/ai/copilot/**\"}");
        assertThat(copilotRule).isGreaterThanOrEqualTo(0);
        assertThat(fallbackRule).isGreaterThan(copilotRule);
        assertThat(controller).contains("@RequestMapping(\"/api/mm/ai/copilot\")");
        assertThat(client).contains("\"/v1/copilot/messages\"");
    }
}

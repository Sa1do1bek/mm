package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TreasuryCallbackApiContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void treasuryCallbackIsSeparatedFromFinanceRolesAndVerifiedBeforePersistence() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/IntegrationCallbackController.java"));
        String security = Files.readString(ROOT.resolve("security/SecurityConfig.java"));

        assertThat(controller).contains(
            "@PreAuthorize(\"hasAnyRole('ADMIN','INTEGRATION_OPERATOR')\")",
            "X-MM-Timestamp",
            "X-MM-Signature",
            "treasuryCallbackAuthenticityPolicy.requireAuthentic("
        );
        assertThat(controller).doesNotContain("FINANCE_CONTROLLER");
        assertThat(security).contains(
            ".requestMatchers(HttpMethod.POST, \"/api/integrations/callbacks/**\").hasAnyRole(\"ADMIN\", \"INTEGRATION_OPERATOR\")"
        );
    }

    @Test
    void treasuryCallbackMustCorrelateToAnOutboundPaymentAndCannotChooseItsEventType() throws Exception {
        String service = Files.readString(ROOT.resolve("service/IntegrationSettingService.java"));
        String repository = Files.readString(ROOT.resolve("repository/IntegrationEventRepository.java"));

        assertThat(service).contains(
            "requireCorrelatedTreasuryCallback(request, headerIdempotencyKey)",
            "TREASURY.PAYMENT_REQUEST",
            "request == null || system == ExternalIntegrationSystem.TREASURY",
            "eventService.recordInboundCallbackIdempotent("
        );
        assertThat(repository).contains(
            "outgoing.eventType = 'TREASURY.PAYMENT_REQUEST'",
            "outgoing.aggregateId = event.aggregateId"
        );
    }
}

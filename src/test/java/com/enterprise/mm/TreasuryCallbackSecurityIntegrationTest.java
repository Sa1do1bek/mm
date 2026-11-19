package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.repository.IntegrationEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "app.seed.enabled=false",
    "app.integrations.systems.treasury.client-secret=treasury-live-callback-secret",
    "app.integrations.systems.treasury.mock-mode=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TreasuryCallbackSecurityIntegrationTest {
    private static final String TENANT = "tenant-treasury-live";
    private static final String SECRET = "treasury-live-callback-secret";

    @Autowired private MockMvc mockMvc;
    @Autowired private IntegrationEventRepository repository;

    @Test
    void signedOperatorCallbackIsCorrelatedTerminalAndUnavailableToFinanceRole() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        repository.saveAndFlush(paymentRequest(companyId, actId));

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String key = "treasury-live-paid-42";
        String externalId = "BANK-LIVE-42";
        String signature = sign(String.join("|",
            "TREASURY", TENANT, companyId.toString(), timestamp, key,
            actId.toString(), externalId, "PAID"
        ));
        String body = """
            {
              "eventType":"CALLER_CANNOT_OVERRIDE_THIS",
              "externalId":"%s",
              "status":"PAID",
              "aggregateId":"%s",
              "payload":{"payment":"confirmed"}
            }
            """.formatted(externalId, actId);

        mockMvc.perform(post("/api/integrations/callbacks/TREASURY")
                .with(user("treasury-adapter").roles("INTEGRATION_OPERATOR"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", companyId)
                .header("Idempotency-Key", key)
                .header("X-MM-Timestamp", timestamp)
                .header("X-MM-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Callback accepted")));

        IntegrationEvent callback = repository
            .findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TENANT, companyId, actId
            ).stream()
            .filter(event -> "TREASURY.PAYMENT_STATUS_PAID".equals(event.getEventType()))
            .findFirst()
            .orElseThrow();
        assertThat(callback.getStatus()).isEqualTo("PUBLISHED");
        assertThat(callback.getExternalId()).isEqualTo(externalId);
        assertThat(repository.findDue(List.of("NEW", "RETRY"), LocalDateTime.now()))
            .extracting(IntegrationEvent::getId)
            .doesNotContain(callback.getId());

        String financeKey = "treasury-finance-forbidden";
        String financeSignature = sign(String.join("|",
            "TREASURY", TENANT, companyId.toString(), timestamp, financeKey,
            actId.toString(), externalId, "PAID"
        ));
        mockMvc.perform(post("/api/integrations/callbacks/TREASURY")
                .with(user("finance-user").roles("FINANCE_CONTROLLER"))
                .header("X-Tenant-Id", TENANT)
                .header("X-Company-Id", companyId)
                .header("Idempotency-Key", financeKey)
                .header("X-MM-Timestamp", timestamp)
                .header("X-MM-Signature", financeSignature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void businessRolesCannotForgeTreasuryAcknowledgementThroughGenericEventCrud() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID actId = UUID.randomUUID();
        String body = """
            {
              "name":"Forged payment acknowledgement",
              "status":"PUBLISHED",
              "eventType":"TREASURY.PAYMENT_STATUS_PAID",
              "aggregateType":"TREASURY",
              "aggregateId":"%s",
              "externalId":"BANK-FORGED",
              "description":"PAID",
              "payload":"{}"
            }
            """.formatted(actId);

        for (String role : List.of("FINANCE_CONTROLLER", "MM_MANAGER")) {
            mockMvc.perform(post("/api/integrations/events")
                    .with(user("business-user").roles(role))
                    .header("X-Tenant-Id", TENANT)
                    .header("X-Company-Id", companyId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isForbidden());
        }
    }

    private IntegrationEvent paymentRequest(UUID companyId, UUID aggregateId) {
        IntegrationEvent event = new IntegrationEvent();
        event.setTenantId(TENANT);
        event.setCompanyId(companyId);
        event.setCode("TREASURY-REQUEST-" + UUID.randomUUID());
        event.setName("Treasury payment request");
        event.setStatus("PUBLISHED");
        event.setEventType("TREASURY.PAYMENT_REQUEST");
        event.setAggregateType("TREASURY");
        event.setAggregateId(aggregateId);
        event.setPayload("{}");
        event.setIdempotencyKey("outbound-payment-" + aggregateId);
        event.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return event;
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.IntegrationProperties;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCallbackRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class TreasuryCallbackAuthenticityPolicyTest {
    private static final String SECRET = "treasury-callback-test-secret";

    @Mock private IntegrationSettingService integrationSettingService;

    private TreasuryCallbackAuthenticityPolicy policy;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-treasury-callback");
        CompanyContext.setCompany(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        policy = new TreasuryCallbackAuthenticityPolicy(integrationSettingService);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void acceptsFreshSignatureOverTheExactPaymentCorrelationFields() {
        configuredSecret();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String key = "treasury-callback-42";
        IntegrationCallbackRequest request = callback("BANK-42", "PAID");
        String signature = sign(TreasuryCallbackAuthenticityPolicy.canonicalMessage(request, key, timestamp));

        assertThatCode(() -> policy.requireAuthentic(
            ExternalIntegrationSystem.TREASURY, request, key, timestamp, signature
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsSignatureWhenPaymentReferenceWasChanged() {
        configuredSecret();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String key = "treasury-callback-43";
        IntegrationCallbackRequest signed = callback("BANK-ORIGINAL", "PAID");
        String signature = sign(TreasuryCallbackAuthenticityPolicy.canonicalMessage(signed, key, timestamp));

        assertThatThrownBy(() -> policy.requireAuthentic(
            ExternalIntegrationSystem.TREASURY,
            callback("BANK-TAMPERED", "PAID"),
            key,
            timestamp,
            signature
        )).isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("signature");
    }

    @Test
    void rejectsStaleTreasuryCallback() {
        String timestamp = Long.toString(
            Instant.now().minusSeconds(TreasuryCallbackAuthenticityPolicy.MAX_CLOCK_SKEW_SECONDS + 1).getEpochSecond()
        );
        String key = "treasury-callback-stale";
        IntegrationCallbackRequest request = callback("BANK-STALE", "PAID");
        String signature = sign(TreasuryCallbackAuthenticityPolicy.canonicalMessage(request, key, timestamp));

        assertThatThrownBy(() -> policy.requireAuthentic(
            ExternalIntegrationSystem.TREASURY, request, key, timestamp, signature
        )).isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("timestamp");
    }

    private IntegrationCallbackRequest callback(String externalId, String status) {
        return new IntegrationCallbackRequest(
            "CALLER_CONTROLLED_EVENT_TYPE",
            externalId,
            status,
            Map.of("payment", externalId),
            null,
            UUID.fromString("20000000-0000-0000-0000-000000000002")
        );
    }

    private IntegrationSettingService.RuntimeEndpoint endpoint(String secret) {
        IntegrationProperties.SystemEndpoint configured = new IntegrationProperties.SystemEndpoint();
        configured.setClientSecret(secret);
        return IntegrationSettingService.RuntimeEndpoint.from(ExternalIntegrationSystem.TREASURY, configured);
    }

    private void configuredSecret() {
        when(integrationSettingService.endpoint(ExternalIntegrationSystem.TREASURY))
            .thenReturn(endpoint(SECRET));
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

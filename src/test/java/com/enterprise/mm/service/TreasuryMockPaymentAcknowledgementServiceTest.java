package com.enterprise.mm.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCallbackRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCallbackResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TreasuryMockPaymentAcknowledgementServiceTest {
    @Mock private IntegrationSettingService integrationSettingService;
    @InjectMocks private TreasuryMockPaymentAcknowledgementService service;

    @Test
    void recordsSameCallbackEvidenceAsLiveTreasuryWhenMockModeEnabled() {
        UUID paymentId = UUID.randomUUID();
        when(integrationSettingService.endpoint(ExternalIntegrationSystem.TREASURY))
            .thenReturn(mockEndpoint(true, true));
        when(integrationSettingService.acceptCallback(eq(ExternalIntegrationSystem.TREASURY), any(), any()))
            .thenReturn(new IntegrationCallbackResponse(UUID.randomUUID(), "TREASURY", "REF-1", "PAID", "ok"));

        service.acknowledgeIfMockMode(paymentId, "REF-1");

        ArgumentCaptor<IntegrationCallbackRequest> request = ArgumentCaptor.forClass(IntegrationCallbackRequest.class);
        verify(integrationSettingService).acceptCallback(
            eq(ExternalIntegrationSystem.TREASURY),
            request.capture(),
            eq("treasury-mock-ack:" + paymentId + ":REF-1")
        );
        IntegrationCallbackRequest body = request.getValue();
        org.assertj.core.api.Assertions.assertThat(body.aggregateId()).isEqualTo(paymentId);
        org.assertj.core.api.Assertions.assertThat(body.externalId()).isEqualTo("REF-1");
        org.assertj.core.api.Assertions.assertThat(body.status()).isEqualTo("PAID");
    }

    @Test
    void doesNothingWhenLiveMode() {
        when(integrationSettingService.endpoint(ExternalIntegrationSystem.TREASURY))
            .thenReturn(mockEndpoint(true, false));

        service.acknowledgeIfMockMode(UUID.randomUUID(), "REF-LIVE");

        verify(integrationSettingService, never()).acceptCallback(any(), any(), any());
    }

    private IntegrationSettingService.RuntimeEndpoint mockEndpoint(boolean enabled, boolean mockMode) {
        return new IntegrationSettingService.RuntimeEndpoint(
            "TREASURY",
            "Treasury",
            enabled,
            mockMode,
            "http://localhost:8082",
            "BASIC",
            null,
            null,
            null,
            "Authorization",
            null,
            null,
            null,
            null,
            "/health",
            "/events",
            "/events",
            "/events",
            "/events",
            null,
            null,
            null,
            null,
            null,
            null,
            java.math.BigDecimal.valueOf(0.2),
            1024,
            30
        );
    }
}

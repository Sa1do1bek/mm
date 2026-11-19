package com.enterprise.mm.service;

import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCallbackRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When Treasury runs in mock mode, records the same inbound payment acknowledgement
 * evidence that a live Treasury callback would produce via
 * {@link IntegrationSettingService#acceptCallback}.
 *
 * <p>Payment completion still goes through {@link TreasuryPaymentAcknowledgementPolicy}
 * and {@code markPaid}. Switching to live integration is only
 * {@code app.integrations.systems.treasury.mock-mode=false} (or the company Integration
 * Setting) plus a real signed callback — no payment flow rewrite.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.MM_FINANCE_EXECUTION, reads = {"isMockMode"})
public class TreasuryMockPaymentAcknowledgementService {
    private final IntegrationSettingService integrationSettingService;

    public boolean isMockMode() {
        IntegrationSettingService.RuntimeEndpoint endpoint =
            integrationSettingService.endpoint(ExternalIntegrationSystem.TREASURY);
        return endpoint.enabled() && endpoint.mockMode();
    }

    @Transactional
    public void acknowledgeIfMockMode(UUID paymentId, String paymentReference) {
        if (paymentId == null || !isMockMode()) {
            return;
        }
        String externalId = paymentReference == null || paymentReference.isBlank()
            ? "MOCK-" + paymentId.toString().substring(0, 8)
            : paymentReference.trim();
        String idempotencyKey = "treasury-mock-ack:" + paymentId + ":" + externalId;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "MOCK");
        payload.put("source", "TreasuryMockPaymentAcknowledgementService");
        payload.put("paymentId", paymentId.toString());
        payload.put("paymentReference", externalId);
        IntegrationCallbackRequest callback = new IntegrationCallbackRequest(
            null,
            externalId,
            "PAID",
            payload,
            idempotencyKey,
            paymentId
        );
        integrationSettingService.acceptCallback(ExternalIntegrationSystem.TREASURY, callback, idempotencyKey);
        log.info(
            "Recorded mock Treasury payment acknowledgement for payment {} reference {}",
            paymentId,
            externalId
        );
    }
}

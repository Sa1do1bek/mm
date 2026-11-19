package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;

public final class MfaDtos {
    private MfaDtos() {
    }

    public record MfaSetupResponse(String secret, String otpauthUri) {
    }

    public record MfaVerifyRequest(String code) {
    }

    public record MfaStatusResponse(boolean enabled, String method, String maskedPhoneNumber, boolean totpConfigured, boolean smsConfigured) {
    }

    public record SmsMfaRequest(String phoneNumber, String code) {
    }

    public record SmsRetryRequest(String purpose) {
    }

    public record SmsDeliveryCallbackRequest(
        String messageId,
        String username,
        String phoneNumber,
        String status,
        String providerStatus,
        LocalDateTime deliveredAt,
        Object payload,
        String idempotencyKey
    ) {
    }

    public record SmsDeliveryCallbackResponse(UUID integrationEventId, String status, String message) {
    }
}
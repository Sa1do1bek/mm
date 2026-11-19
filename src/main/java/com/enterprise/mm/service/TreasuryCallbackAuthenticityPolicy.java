package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCallbackRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Verifies the independent Treasury service callback before it can become
 * payment evidence. Human finance roles never receive the shared secret.
 */
@Service
@RequiredArgsConstructor
public class TreasuryCallbackAuthenticityPolicy {
    static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private final IntegrationSettingService integrationSettingService;

    public void requireAuthentic(
        ExternalIntegrationSystem system,
        IntegrationCallbackRequest request,
        String headerIdempotencyKey,
        String timestamp,
        String signature
    ) {
        if (system != ExternalIntegrationSystem.TREASURY) {
            return;
        }
        if (request == null || request.aggregateId() == null || blank(request.externalId()) || blank(request.status())) {
            throw new BadRequestException(
                "Treasury callback requires aggregateId, externalId and status"
            );
        }
        String idempotencyKey = firstNonBlank(headerIdempotencyKey, request.idempotencyKey());
        if (blank(idempotencyKey)) {
            throw new BadRequestException("Idempotency-Key is required for Treasury callbacks");
        }
        long issuedAt = parseTimestamp(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - issuedAt) > MAX_CLOCK_SKEW_SECONDS) {
            throw new AccessDeniedException("Treasury callback timestamp is outside the accepted window");
        }
        String secret = integrationSettingService.endpoint(system).clientSecret();
        if (blank(secret)) {
            throw new AccessDeniedException("Treasury callback signing secret is not configured");
        }
        byte[] expected = hmac(secret, canonicalMessage(request, idempotencyKey, timestamp));
        byte[] supplied = decodeSignature(signature);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new AccessDeniedException("Treasury callback signature is invalid");
        }
    }

    static String canonicalMessage(
        IntegrationCallbackRequest request,
        String idempotencyKey,
        String timestamp
    ) {
        return String.join("|",
            ExternalIntegrationSystem.TREASURY.name(),
            TenantContext.currentTenant(),
            CompanyContext.currentCompany().toString(),
            timestamp.trim(),
            idempotencyKey.trim(),
            request.aggregateId().toString(),
            request.externalId().trim(),
            request.status().trim().toUpperCase(java.util.Locale.ROOT)
        );
    }

    private long parseTimestamp(String timestamp) {
        if (blank(timestamp)) {
            throw new AccessDeniedException("X-MM-Timestamp is required for Treasury callbacks");
        }
        try {
            return Long.parseLong(timestamp.trim());
        } catch (NumberFormatException exception) {
            throw new AccessDeniedException("Treasury callback timestamp is invalid");
        }
    }

    private byte[] decodeSignature(String signature) {
        if (blank(signature)) {
            throw new AccessDeniedException("X-MM-Signature is required for Treasury callbacks");
        }
        try {
            return HexFormat.of().parseHex(signature.trim());
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("Treasury callback signature is invalid");
        }
    }

    private byte[] hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot verify Treasury callback signature", exception);
        }
    }

    private String firstNonBlank(String first, String second) {
        return blank(first) ? second : first;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

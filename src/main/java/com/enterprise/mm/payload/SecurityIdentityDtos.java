package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;

public final class SecurityIdentityDtos {
    private SecurityIdentityDtos() {
    }

    public record SecurityOptionsResponse(
        boolean ssoEnabled,
        String ssoProviderType,
        String ssoLoginUrl,
        boolean smsMfaEnabled,
        boolean totpMfaEnabled
    ) {
    }

    public record SsoAssertionRequest(
        String tenantId,
        UUID companyId,
        String username,
        String email,
        String fullName,
        String externalSubject,
        List<String> roles,
        long issuedAtEpochSeconds,
        String nonce,
        String signature
    ) {
    }

    public record SsoLoginUrlResponse(
        String authorizationUrl,
        String state,
        long expiresAtEpochSeconds
    ) {
    }

    public record SsoOidcCallbackRequest(
        String code,
        String state
    ) {
    }

    public record SsoSamlAcsRequest(
        String tenantId,
        UUID companyId,
        String samlResponse,
        String relayState
    ) {
    }
}
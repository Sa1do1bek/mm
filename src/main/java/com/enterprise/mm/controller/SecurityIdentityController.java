package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AuthDtos.LoginResponse;
import com.enterprise.mm.payload.SecurityIdentityDtos.SecurityOptionsResponse;
import com.enterprise.mm.payload.SecurityIdentityDtos.SsoAssertionRequest;
import com.enterprise.mm.payload.SecurityIdentityDtos.SsoLoginUrlResponse;
import com.enterprise.mm.payload.SecurityIdentityDtos.SsoOidcCallbackRequest;
import com.enterprise.mm.payload.SecurityIdentityDtos.SsoSamlAcsRequest;
import com.enterprise.mm.payload.SsoSettingsDto;
import com.enterprise.mm.service.SmsGatewayClient;
import com.enterprise.mm.service.SsoAssertionService;
import com.enterprise.mm.service.SsoOidcService;
import com.enterprise.mm.service.SsoSamlService;
import com.enterprise.mm.service.SsoSettingsService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SecurityIdentityController {
    private final SsoSettingsService ssoSettingsService;
    private final SsoAssertionService ssoAssertionService;
    private final SsoOidcService ssoOidcService;
    private final SsoSamlService ssoSamlService;
    private final SmsGatewayClient smsGatewayClient;

    @GetMapping("/security-options")
    public ApiResponse<SecurityOptionsResponse> securityOptions() {
        SsoSettingsDto sso = ssoSettingsService.get();
        return ApiResponse.ok(new SecurityOptionsResponse(
            sso.isEnabled(),
            sso.getProviderType(),
            firstNonBlank(sso.getLoginUrl(), sso.getAuthorizationUrl()),
            smsGatewayClient.mfaEnabled(),
            true
        ));
    }

    @PostMapping("/sso/assertion")
    public ApiResponse<LoginResponse> ssoAssertion(@Valid @RequestBody SsoAssertionRequest request) {
        return ApiResponse.ok(ssoAssertionService.authenticate(request));
    }

    @GetMapping("/sso/login-url")
    public ApiResponse<SsoLoginUrlResponse> ssoLoginUrl(@RequestParam(required = false) String tenantId, @RequestParam(required = false) UUID companyId) {
        SsoSettingsDto settings = ssoSettingsService.get();
        if ("SAML".equalsIgnoreCase(settings.getProviderType())) {
            return ApiResponse.ok(ssoSamlService.loginUrl(tenantId, companyId));
        }
        return ApiResponse.ok(ssoOidcService.loginUrl(tenantId, companyId));
    }

    @PostMapping("/sso/oidc/callback")
    public ApiResponse<LoginResponse> ssoOidcCallback(@Valid @RequestBody SsoOidcCallbackRequest request) {
        return ApiResponse.ok(ssoOidcService.callback(request));
    }

    @PostMapping(value = "/sso/saml/acs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<LoginResponse> ssoSamlAcs(@Valid @RequestBody SsoSamlAcsRequest request) {
        return ApiResponse.ok(ssoSamlService.acs(request));
    }

    @PostMapping(value = "/sso/saml/acs", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ApiResponse<LoginResponse> ssoSamlAcsForm(
        @RequestParam("SAMLResponse") String samlResponse,
        @RequestParam(required = false) String RelayState,
        @RequestParam(required = false) String tenantId,
        @RequestParam(required = false) UUID companyId
    ) {
        return ApiResponse.ok(ssoSamlService.acs(new SsoSamlAcsRequest(tenantId, companyId, samlResponse, RelayState)));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

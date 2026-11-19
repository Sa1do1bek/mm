package com.enterprise.mm.service;

import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationHealthDto;
import com.enterprise.mm.payload.ProductionPreflightDtos.ProductionPreflightCheck;
import com.enterprise.mm.payload.ProductionPreflightDtos.ProductionPreflightReport;
import com.enterprise.mm.service.SsoSettingsService.RuntimeSsoSettings;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.config.AttachmentProperties;
import com.enterprise.mm.config.IntegrationProperties;
import com.enterprise.mm.config.SecurityIdentityProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionReadinessService {
    private static final EnumSet<ExternalIntegrationSystem> OPTIONAL_AI_INTEGRATIONS = EnumSet.of(
        ExternalIntegrationSystem.CHATGPT,
        ExternalIntegrationSystem.CLAUDE,
        ExternalIntegrationSystem.GEMINI
    );
    private static final EnumSet<ExternalIntegrationSystem> REQUIRED_INTEGRATIONS = EnumSet.complementOf(OPTIONAL_AI_INTEGRATIONS);
    private static final EnumSet<ExternalIntegrationSystem> CERTIFIED_PROVIDERS = EnumSet.of(
        ExternalIntegrationSystem.SOLIQ,
        ExternalIntegrationSystem.DIDOX,
        ExternalIntegrationSystem.FAKTURA_UZ,
        ExternalIntegrationSystem.E_IMZO
    );
    private final IntegrationSettingService integrationSettingService;
    private final SsoSettingsService ssoSettingsService;
    private final SecurityIdentityProperties identityProperties;
    private final AttachmentProperties attachmentProperties;
    private final IntegrationProperties integrationProperties;
    private final HaLoadDrEvidenceService haLoadDrEvidenceService;
    private final Environment environment;
    private final String jwtSecret;
    public ProductionReadinessService(
        IntegrationSettingService integrationSettingService,
        SsoSettingsService ssoSettingsService,
        SecurityIdentityProperties identityProperties,
        AttachmentProperties attachmentProperties,
        IntegrationProperties integrationProperties,
        HaLoadDrEvidenceService haLoadDrEvidenceService,
        Environment environment,
        @Value("${app.jwt.secret}") String jwtSecret
    ) {
        this.integrationSettingService = integrationSettingService;
        this.ssoSettingsService = ssoSettingsService;
        this.identityProperties = identityProperties;
        this.attachmentProperties = attachmentProperties;
        this.integrationProperties = integrationProperties;
        this.haLoadDrEvidenceService = haLoadDrEvidenceService;
        this.environment = environment;
        this.jwtSecret = jwtSecret;
    }
    @Transactional(readOnly = true)
    public ProductionPreflightReport report(boolean liveCheck) {
        List<ProductionPreflightCheck> checks = new ArrayList<>();
        checks.addAll(securityChecks());
        checks.addAll(identityChecks());
        checks.add(attachmentCheck());
        checks.addAll(integrationChecks(liveCheck));
        checks.addAll(manualGateChecks());
        Map<String, Long> summary = checks.stream()
            .collect(Collectors.groupingBy(ProductionPreflightCheck::status, LinkedHashMap::new, Collectors.counting()));
        boolean hasBlockingFail = checks.stream().anyMatch(check -> check.blocksProduction() && "FAIL".equals(check.status()));
        boolean hasNonPass = checks.stream().anyMatch(check -> !"PASS".equals(check.status()));
        String status = hasBlockingFail ? "FAIL" : (hasNonPass ? "WARN" : "PASS");
        return new ProductionPreflightReport(status, !hasBlockingFail, LocalDateTime.now(), summary, checks);
    }
    private List<ProductionPreflightCheck> securityChecks() {
        List<ProductionPreflightCheck> checks = new ArrayList<>();
        checks.add(secretCheck(
            "SECURITY",
            "security.jwt.secret",
            jwtSecret,
            "JWT secret is production-grade",
            "JWT_SECRET is missing or uses a default development value",
            "Set JWT_SECRET to a unique high-entropy value with at least 32 characters"
        ));
        checks.add(secretCheck(
            "SECURITY",
            "security.integration-settings.secret",
            integrationProperties.getSettingsSecret(),
            "Integration settings encryption secret is production-grade",
            "Integration settings secret is missing or uses a default value",
            "Set INTEGRATION_SETTINGS_SECRET to a unique high-entropy value"
        ));
        checks.add(secretCheck(
            "SECURITY",
            "security.sensitive-field.secret",
            environment.getProperty("SENSITIVE_FIELD_KEY", System.getenv("SENSITIVE_FIELD_KEY")),
            "Sensitive field encryption key is production-grade",
            "Sensitive field encryption key is missing or uses a default value",
            "Set SENSITIVE_FIELD_KEY to a unique high-entropy value"
        ));
        return checks;
    }
    private List<ProductionPreflightCheck> identityChecks() {
        List<ProductionPreflightCheck> checks = new ArrayList<>();
        RuntimeSsoSettings sso = ssoSettingsService.runtime(null, null);
        if (!sso.enabled()) {
            checks.add(fail("IDENTITY", "identity.sso.enabled", true, "SSO is disabled", List.of(
                "Set SSO_ENABLED=true",
                "Configure corporate OIDC/SAML gateway metadata and credentials"
            )));
        } else {
            String providerType = safe(sso.providerType()).toUpperCase(Locale.ROOT);
            List<String> missing;
            if ("SAML".equals(providerType)) {
                missing = missing(
                    item("SSO_LOGIN_URL", sso.loginUrl()),
                    item("SSO_METADATA_URL", sso.metadataUrl()),
                    item("SSO_IDP_ENTITY_ID", sso.idpEntityId()),
                    item("SSO_SP_ENTITY_ID", sso.spEntityId()),
                    item("SSO_ACS_URL", sso.acsUrl()),
                    item("SSO_X509_CERTIFICATE", sso.x509Certificate()),
                    item("SSO_ASSERTION_SECRET", sso.assertionSecret())
                );
                checks.add(missing.isEmpty()
                    ? pass("IDENTITY", "identity.sso.saml", "SAML SSO metadata, certificates and assertion gateway are configured")
                    : fail("IDENTITY", "identity.sso.saml", true, "SAML SSO configuration is incomplete: " + String.join(", ", missing), List.of(
                        "Fill SAML metadata/certificate fields in Settings -> Integrations -> SSO",
                        "Run /api/settings/sso/test after saving IdP metadata"
                    )));
            } else {
                missing = missing(
                    item("SSO_AUTHORIZATION_URL", sso.authorizationUrl()),
                    item("SSO_TOKEN_URL", sso.tokenUrl()),
                    item("SSO_USER_INFO_URL", sso.userInfoUrl()),
                    item("SSO_CLIENT_ID", sso.clientId()),
                    item("SSO_REDIRECT_URI", sso.redirectUri())
                );
                if (requiresClientSecret(sso) && blank(sso.clientSecret())) {
                    missing.add("SSO_CLIENT_SECRET");
                }
                checks.add(missing.isEmpty()
                    ? pass("IDENTITY", "identity.sso.oidc", "OIDC SSO code flow is configured")
                    : fail("IDENTITY", "identity.sso.oidc", true, "OIDC SSO configuration is incomplete: " + String.join(", ", missing), List.of(
                        "Fill OIDC metadata and client credentials in Settings -> Integrations -> SSO",
                        "Run /api/auth/sso/login-url and /api/auth/sso/oidc/callback against the corporate IdP"
                    )));
            }
            checks.add(blank(sso.assertionSecret())
                ? warn("IDENTITY", "identity.sso.assertion", true, "Signed SSO assertion secret is not configured", List.of("Set SSO_ASSERTION_SECRET for SAML/OIDC gateway assertions"))
                : pass("IDENTITY", "identity.sso.assertion", "Signed SSO assertion secret is configured"));
        }
        SecurityIdentityProperties.SmsMfa sms = identityProperties.getSmsMfa();
        if (!sms.isEnabled()) {
            checks.add(warn("IDENTITY", "identity.sms.enabled", false, "SMS MFA is disabled; TOTP MFA remains available", List.of("Set SMS_MFA_ENABLED=true if SMS MFA is required by policy")));
        } else if (sms.getGateway() == null || !sms.getGateway().isEnabled()) {
            checks.add(warn("IDENTITY", "identity.sms.gateway", false, "SMS MFA is enabled but direct SMS gateway is disabled", List.of("Set SMS_GATEWAY_ENABLED=true and SMS_GATEWAY_URL for direct delivery")));
        } else {
            SecurityIdentityProperties.SmsMfa.Gateway gateway = sms.getGateway();
            List<String> missing = smsGatewayMissing(gateway);
            String provider = safe(gateway.getProviderCode()).isBlank() ? "CUSTOM" : safe(gateway.getProviderCode()).toUpperCase(Locale.ROOT);
            checks.add(missing.isEmpty()
                ? pass("IDENTITY", "identity.sms.gateway", "SMS gateway is configured for " + provider)
                : fail("IDENTITY", "identity.sms.gateway", false, "SMS gateway configuration is incomplete: " + String.join(", ", missing), List.of(
                    "Fill SMS_GATEWAY_* variables or save provider credentials in Settings",
                    "Run /api/settings/sms/test before enabling production SMS MFA"
                )));
        }
        return checks;
    }
    private List<String> smsGatewayMissing(SecurityIdentityProperties.SmsMfa.Gateway gateway) {
        String provider = safe(gateway.getProviderCode()).replace('-', '_').toUpperCase(Locale.ROOT);
        if (provider.isBlank()) {
            provider = "CUSTOM";
        }
        List<String> missing = new ArrayList<>();
        if (blank(gateway.getUrl()) && !"ESKIZ".equals(provider) && !"TWILIO".equals(provider)) {
            missing.add("SMS_GATEWAY_URL");
        }
        if ("ESKIZ".equals(provider)) {
            if (blank(gateway.getLogin())) {
                missing.add("SMS_GATEWAY_LOGIN");
            }
            if (blank(gateway.getPassword()) && blank(gateway.getAuthToken())) {
                missing.add("SMS_GATEWAY_PASSWORD or SMS_GATEWAY_AUTH_TOKEN");
            }
        } else if ("TWILIO".equals(provider)) {
            if (blank(gateway.getAccountSid())) {
                missing.add("SMS_GATEWAY_ACCOUNT_SID");
            }
            if (blank(gateway.getAuthToken())) {
                missing.add("SMS_GATEWAY_AUTH_TOKEN");
            }
            if (blank(gateway.getServiceSid()) && blank(gateway.getFromPhone()) && blank(gateway.getSender())) {
                missing.add("SMS_GATEWAY_SERVICE_SID or SMS_GATEWAY_FROM_PHONE");
            }
        } else if ("PLAYMOBILE".equals(provider)) {
            if (blank(gateway.getUrl())) {
                missing.add("SMS_GATEWAY_URL");
            }
            if (blank(gateway.getAuthToken()) && (blank(gateway.getLogin()) || blank(gateway.getPassword()))) {
                missing.add("SMS_GATEWAY_AUTH_TOKEN or SMS_GATEWAY_LOGIN/PASSWORD");
            }
        }
        return missing;
    }
    private ProductionPreflightCheck attachmentCheck() {
        String provider = safe(attachmentProperties.getStorageProvider()).toUpperCase(Locale.ROOT);
        if ("S3".equals(provider) || "MINIO".equals(provider)) {
            List<String> missing = missing(
                item("ATTACHMENT_S3_ENDPOINT", attachmentProperties.getS3Endpoint()),
                item("ATTACHMENT_S3_BUCKET", attachmentProperties.getS3Bucket()),
                item("ATTACHMENT_S3_ACCESS_KEY", attachmentProperties.getS3AccessKey()),
                item("ATTACHMENT_S3_SECRET_KEY", attachmentProperties.getS3SecretKey())
            );
            return missing.isEmpty()
                ? pass("STORAGE", "attachments.storage", provider + " attachment storage is configured")
                : fail("STORAGE", "attachments.storage", true, provider + " attachment storage is incomplete: " + String.join(", ", missing), List.of("Fill S3/MinIO attachment variables"));
        }
        return fail("STORAGE", "attachments.storage", true, "Attachment storage is LOCAL; this is not HA-ready for SaaS production", List.of(
            "Set ATTACHMENT_STORAGE_PROVIDER=MINIO or S3",
            "Configure endpoint, bucket and credentials"
        ));
    }
    private List<ProductionPreflightCheck> integrationChecks(boolean liveCheck) {
        List<ProductionPreflightCheck> checks = new ArrayList<>();
        for (ExternalIntegrationSystem system : REQUIRED_INTEGRATIONS) {
            IntegrationSettingService.RuntimeEndpoint endpoint = integrationSettingService.endpoint(system);
            checks.add(integrationModeCheck(system, endpoint));
            checks.add(integrationCredentialCheck(system, endpoint));
            if (liveCheck && endpoint.enabled() && endpoint.configured() && !endpoint.mockMode()) {
                checks.add(integrationHealthCheck(system));
            }
        }
        return checks;
    }
    private ProductionPreflightCheck integrationModeCheck(ExternalIntegrationSystem system, IntegrationSettingService.RuntimeEndpoint endpoint) {
        String code = "integration." + system.name() + ".mode";
        if (!endpoint.enabled()) {
            return fail("INTEGRATION", code, true, system.displayName() + " adapter is disabled", List.of("Enable the adapter or remove it from production scope"));
        }
        if (!endpoint.configured()) {
            return fail("INTEGRATION", code, true, system.displayName() + " base URL is not configured", List.of("Set " + system.name() + "_BASE_URL or save provider settings"));
        }
        if (endpoint.mockMode()) {
            return fail("INTEGRATION", code, true, system.displayName() + " is still in mock/outbox mode", List.of("Set " + system.name() + "_MOCK_MODE=false after provider test access is issued"));
        }
        return pass("INTEGRATION", code, system.displayName() + " is configured for live dispatch");
    }
    private ProductionPreflightCheck integrationCredentialCheck(ExternalIntegrationSystem system, IntegrationSettingService.RuntimeEndpoint endpoint) {
        String code = "integration." + system.name() + ".credentials";
        String authType = safe(endpoint.authType()).toUpperCase(Locale.ROOT);
        boolean certified = CERTIFIED_PROVIDERS.contains(system);
        boolean configured = switch (authType) {
            case "BASIC" -> !blank(endpoint.username()) && !blank(endpoint.password());
            case "BEARER" -> !blank(endpoint.bearerToken());
            case "API_KEY" -> !blank(endpoint.apiKey());
            case "OAUTH2" -> !blank(endpoint.clientId()) && !blank(endpoint.clientSecret());
            default -> !certified;
        };
        if (configured) {
            return pass("INTEGRATION", code, system.displayName() + " credentials are configured for auth type " + authType);
        }
        return fail("INTEGRATION", code, certified, system.displayName() + " credentials are missing for auth type " + authType, List.of(
            "Save credentials in Settings -> Integrations",
            "Do not store provider credentials in source code"
        ));
    }
    private ProductionPreflightCheck integrationHealthCheck(ExternalIntegrationSystem system) {
        IntegrationHealthDto health = integrationSettingService.health(system);
        if ("LIVE_OK".equals(health.status())) {
            return pass("INTEGRATION", "integration." + system.name() + ".health", system.displayName() + " health endpoint is reachable");
        }
        return fail("INTEGRATION", "integration." + system.name() + ".health", true, system.displayName() + " health check failed: " + health.message(), List.of(
            "Check provider network allowlist, TLS certificates, base URL and health path"
        ));
    }
    private List<ProductionPreflightCheck> manualGateChecks() {
        List<ProductionPreflightCheck> checks = new ArrayList<>(haLoadDrEvidenceService.checks());
        checks.addAll(certificationChecks());
        checks.add(approvalCheck("OPERATIONS", "operations.warehouse-devices", "WAREHOUSE_DEVICE_UAT_APPROVED", "Warehouse device UAT is approved", "Run handheld scanner/RFID UAT and set WAREHOUSE_DEVICE_UAT_APPROVED=true"));
        return checks;
    }
    private List<ProductionPreflightCheck> certificationChecks() {
        List<ProductionPreflightCheck> checks = new ArrayList<>();
        for (ExternalIntegrationSystem system : CERTIFIED_PROVIDERS) {
            String prefix = system.name();
            checks.add(approvalCheck(
                "CERTIFICATION",
                "certification." + prefix + ".provider",
                prefix + "_CERTIFICATION_APPROVED",
                system.displayName() + " provider certification is approved",
                "Complete " + system.displayName() + " certification/testing stand and set " + prefix + "_CERTIFICATION_APPROVED=true"
            ));
            checks.add(approvalCheck(
                "CERTIFICATION",
                "certification." + prefix + ".contract",
                prefix + "_PROVIDER_CONTRACT_APPROVED",
                system.displayName() + " production contract/access is approved",
                "Approve provider contract/access package and set " + prefix + "_PROVIDER_CONTRACT_APPROVED=true"
            ));
        }
        checks.add(approvalCheck("CERTIFICATION", "certification.external.overall", "EXTERNAL_CERTIFICATION_APPROVED", "Overall external certification package is approved", "Set EXTERNAL_CERTIFICATION_APPROVED=true after all provider certificates are accepted"));
        return checks;
    }
    private ProductionPreflightCheck approvalCheck(String area, String code, String property, String okMessage, String action) {
        return truthy(environment.getProperty(property, System.getenv(property)))
            ? pass(area, code, okMessage)
            : fail(area, code, true, property + " is not approved", List.of(action));
    }
    private ProductionPreflightCheck secretCheck(String area, String code, String value, String ok, String bad, String action) {
        return weakSecret(value) ? fail(area, code, true, bad, List.of(action)) : pass(area, code, ok);
    }
    private boolean requiresClientSecret(RuntimeSsoSettings sso) {
        String method = safe(sso.clientAuthMethod()).toUpperCase(Locale.ROOT);
        return "BASIC".equals(method) || "POST".equals(method);
    }
    @SafeVarargs
    private final List<String> missing(Map.Entry<String, String>... items) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> item : items) {
            if (blank(item.getValue())) {
                values.add(item.getKey());
            }
        }
        return values;
    }
    private Map.Entry<String, String> item(String key, String value) {
        return Map.entry(key, value == null ? "" : value);
    }
    private ProductionPreflightCheck pass(String area, String code, String message) {
        return new ProductionPreflightCheck(area, code, "PASS", false, message, List.of());
    }
    private ProductionPreflightCheck warn(String area, String code, boolean blocksProduction, String message, List<String> actions) {
        return new ProductionPreflightCheck(area, code, "WARN", blocksProduction, message, actions);
    }
    private ProductionPreflightCheck fail(String area, String code, boolean blocksProduction, String message, List<String> actions) {
        return new ProductionPreflightCheck(area, code, "FAIL", blocksProduction, message, actions);
    }
    private boolean weakSecret(String value) {
        String safe = safe(value);
        return safe.length() < 32 || safe.toLowerCase(Locale.ROOT).contains("change-this");
    }
    private boolean truthy(String value) {
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value);
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

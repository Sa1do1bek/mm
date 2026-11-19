package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.SsoSettingsDto;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.SsoSettingsMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SsoSetting;
import com.enterprise.mm.config.SecurityIdentityProperties;
import com.enterprise.mm.repository.SsoSettingRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Настройки SSO/IdP, хранящиеся в БД (client secret шифруется).
 * При отсутствии записи используются значения из application.yml.
 */
@Service
@RequiredArgsConstructor
public class SsoSettingsService {
    private final SsoSettingRepository repository;
    private final SecurityIdentityProperties properties;
    private final SsoSettingsMapper ssoSettingsMapper;

    @Transactional(readOnly = true)
    public SsoSettingsDto get() {
        SsoSetting row = currentRow();
        return row == null || !configuredRow(row) ? ssoSettingsMapper.defaults(properties) : ssoSettingsMapper.toDto(row);
    }
    @Transactional
    public SsoSettingsDto save(SsoSettingsDto dto) {
        SsoSetting row = currentRow();
        if (row == null) {
            row = new SsoSetting();
            row.setCode("SSO");
            row.setName("SSO / Identity Provider");
            row.setStatus("ACTIVE");
        }
        row.setEnabled(dto.isEnabled());
        row.setProviderType(blank(dto.getProviderType()) ? "OIDC" : dto.getProviderType().trim().toUpperCase());
        row.setLoginUrl(trimToNull(dto.getLoginUrl()));
        row.setAuthorizationUrl(trimToNull(dto.getAuthorizationUrl()));
        row.setTokenUrl(trimToNull(dto.getTokenUrl()));
        row.setUserInfoUrl(trimToNull(dto.getUserInfoUrl()));
        row.setClientId(trimToNull(dto.getClientId()));
        if (!blank(dto.getClientSecret())) {
            row.setClientSecret(dto.getClientSecret().trim());
        }
        row.setRedirectUri(trimToNull(dto.getRedirectUri()));
        row.setScopes(blank(dto.getScopes()) ? "openid profile email" : dto.getScopes().trim());
        row.setTrustsIdpMfa(dto.isTrustsIdpMfa());
        row.setMetadataUrl(trimToNull(dto.getMetadataUrl()));
        row.setIdpEntityId(trimToNull(dto.getIdpEntityId()));
        row.setSpEntityId(trimToNull(dto.getSpEntityId()));
        row.setAcsUrl(trimToNull(dto.getAcsUrl()));
        row.setX509Certificate(trimToNull(dto.getX509Certificate()));
        if (!blank(dto.getSamlPrivateKey())) {
            row.setSamlPrivateKey(dto.getSamlPrivateKey().trim());
        }
        if (!blank(dto.getAssertionSecret())) {
            row.setAssertionSecret(dto.getAssertionSecret().trim());
        }
        if (!blank(dto.getStateSecret())) {
            row.setStateSecret(dto.getStateSecret().trim());
        }
        return ssoSettingsMapper.toDto(repository.save(row));
    }
    @Transactional(readOnly = true)
    public String test() {
        RuntimeSsoSettings runtime = runtime(TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (!runtime.enabled()) {
            throw new BusinessException("SSO выключен");
        }
        List<String> missing = new ArrayList<>();
        if ("OIDC".equalsIgnoreCase(runtime.providerType())) {
            if (blank(runtime.authorizationUrl())) missing.add("authorizationUrl");
            if (blank(runtime.tokenUrl())) missing.add("tokenUrl");
            if (blank(runtime.clientId())) missing.add("clientId");
            if (requiresClientSecret(runtime) && blank(runtime.clientSecret())) missing.add("clientSecret");
            if (blank(runtime.redirectUri())) missing.add("redirectUri");
        } else if ("SAML".equalsIgnoreCase(runtime.providerType())) {
            if (blank(runtime.loginUrl())) missing.add("loginUrl");
            if (blank(runtime.metadataUrl())) missing.add("metadataUrl");
            if (blank(runtime.idpEntityId())) missing.add("idpEntityId");
            if (blank(runtime.spEntityId())) missing.add("spEntityId");
            if (blank(runtime.acsUrl())) missing.add("acsUrl");
            if (blank(runtime.x509Certificate())) missing.add("x509Certificate");
            if (blank(runtime.assertionSecret())) missing.add("assertionSecret");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("Не заполнены поля: " + String.join(", ", missing));
        }
        return "Конфигурация SSO (" + runtime.providerType() + ") заполнена корректно";
    }
    @Transactional(readOnly = true)
    public RuntimeSsoSettings runtime(String tenantId, UUID companyId) {
        String normalizedTenant = TenantContext.normalize(tenantId);
        UUID normalizedCompany = companyId == null ? CompanyContext.DEFAULT_COMPANY_ID : companyId;
        SsoSetting row = repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(normalizedTenant, normalizedCompany)
            .or(() -> repository.findFirstByTenantIdAndIsDeletedFalseOrderByCreatedAtAsc(normalizedTenant))
            .orElse(null);
        if (!configuredRow(row)) {
            row = null;
        }
        var configured = properties.getSso();
        List<String> allowedRoles = configured.getAllowedRoles() == null || configured.getAllowedRoles().isEmpty()
            ? List.of("VIEWER")
            : configured.getAllowedRoles();
        return new RuntimeSsoSettings(
            row == null ? configured.isEnabled() : row.isEnabled(),
            firstNonBlank(row == null ? null : row.getProviderType(), configured.getProviderType(), "OIDC").toUpperCase(Locale.ROOT),
            firstNonBlank(row == null ? null : row.getLoginUrl(), configured.getLoginUrl()),
            firstNonBlank(row == null ? null : row.getAuthorizationUrl(), configured.getAuthorizationUrl()),
            firstNonBlank(row == null ? null : row.getTokenUrl(), configured.getTokenUrl()),
            firstNonBlank(row == null ? null : row.getUserInfoUrl(), configured.getUserInfoUrl()),
            firstNonBlank(row == null ? null : row.getClientId(), configured.getClientId()),
            firstNonBlank(row == null ? null : row.getClientSecret(), configured.getClientSecret()),
            firstNonBlank(row == null ? null : row.getRedirectUri(), configured.getRedirectUri()),
            firstNonBlank(configured.getClientAuthMethod(), "BASIC").toUpperCase(),
            scopes(firstNonBlank(row == null ? null : row.getScopes(), configured.getScopes() == null ? null : String.join(" ", configured.getScopes()))),
            row == null ? configured.isTrustsIdpMfa() : row.isTrustsIdpMfa(),
            configured.getTimestampSkewSeconds(),
            firstNonBlank(row == null ? null : row.getAssertionSecret(), configured.getAssertionSecret()),
            firstNonBlank(row == null ? null : row.getStateSecret(), configured.getStateSecret(), configured.getAssertionSecret()),
            allowedRoles,
            firstNonBlank(row == null ? null : row.getMetadataUrl(), configured.getMetadataUrl()),
            firstNonBlank(row == null ? null : row.getIdpEntityId(), configured.getIdpEntityId()),
            firstNonBlank(row == null ? null : row.getSpEntityId(), configured.getSpEntityId()),
            firstNonBlank(row == null ? null : row.getAcsUrl(), configured.getAcsUrl()),
            firstNonBlank(row == null ? null : row.getX509Certificate(), configured.getX509Certificate()),
            firstNonBlank(row == null ? null : row.getSamlPrivateKey(), configured.getSamlPrivateKey())
        );
    }
    private SsoSetting currentRow() {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .or(() -> repository.findFirstByTenantIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant()))
            .orElse(null);
    }
    private boolean configuredRow(SsoSetting row) {
        return row != null && (row.isEnabled()
            || !blank(row.getLoginUrl())
            || !blank(row.getAuthorizationUrl())
            || !blank(row.getTokenUrl())
            || !blank(row.getUserInfoUrl())
            || !blank(row.getClientId())
            || !blank(row.getClientSecret())
            || !blank(row.getRedirectUri())
            || !blank(row.getMetadataUrl())
            || !blank(row.getIdpEntityId())
            || !blank(row.getSpEntityId())
            || !blank(row.getAcsUrl())
            || !blank(row.getX509Certificate())
            || !blank(row.getSamlPrivateKey())
            || !blank(row.getAssertionSecret())
            || !blank(row.getStateSecret()));
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
    private String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }
    private boolean requiresClientSecret(RuntimeSsoSettings runtime) {
        String method = runtime.clientAuthMethod() == null ? "" : runtime.clientAuthMethod().toUpperCase();
        return "BASIC".equals(method) || "POST".equals(method);
    }
    private List<String> scopes(String value) {
        if (blank(value)) {
            return List.of("openid", "profile", "email");
        }
        return Arrays.stream(value.split("[,\\s]+"))
            .filter(item -> !blank(item))
            .distinct()
            .toList();
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return null;
    }
    public record RuntimeSsoSettings(
        boolean enabled,
        String providerType,
        String loginUrl,
        String authorizationUrl,
        String tokenUrl,
        String userInfoUrl,
        String clientId,
        String clientSecret,
        String redirectUri,
        String clientAuthMethod,
        List<String> scopes,
        boolean trustsIdpMfa,
        long timestampSkewSeconds,
        String assertionSecret,
        String stateSecret,
        List<String> allowedRoles,
        String metadataUrl,
        String idpEntityId,
        String spEntityId,
        String acsUrl,
        String x509Certificate,
        String samlPrivateKey
    ) {
    }
}


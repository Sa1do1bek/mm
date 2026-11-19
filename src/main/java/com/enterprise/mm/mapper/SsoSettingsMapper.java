package com.enterprise.mm.mapper;

import com.enterprise.mm.config.SecurityIdentityProperties;
import com.enterprise.mm.entity.SsoSetting;
import com.enterprise.mm.payload.SsoSettingsDto;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

@Component
public class SsoSettingsMapper {
    public SsoSettingsDto toDto(SsoSetting row) {
        SsoSettingsDto dto = new SsoSettingsDto();
        dto.setEnabled(row.isEnabled());
        dto.setProviderType(row.getProviderType());
        dto.setLoginUrl(row.getLoginUrl());
        dto.setAuthorizationUrl(row.getAuthorizationUrl());
        dto.setTokenUrl(row.getTokenUrl());
        dto.setUserInfoUrl(row.getUserInfoUrl());
        dto.setClientId(row.getClientId());
        dto.setClientSecret(null);
        dto.setClientSecretConfigured(!blank(row.getClientSecret()));
        dto.setRedirectUri(row.getRedirectUri());
        dto.setScopes(row.getScopes());
        dto.setTrustsIdpMfa(row.isTrustsIdpMfa());
        dto.setMetadataUrl(row.getMetadataUrl());
        dto.setIdpEntityId(row.getIdpEntityId());
        dto.setSpEntityId(row.getSpEntityId());
        dto.setAcsUrl(row.getAcsUrl());
        dto.setX509Certificate(row.getX509Certificate());
        dto.setSamlPrivateKey(null);
        dto.setSamlPrivateKeyConfigured(!blank(row.getSamlPrivateKey()));
        dto.setAssertionSecret(null);
        dto.setAssertionSecretConfigured(!blank(row.getAssertionSecret()));
        dto.setStateSecret(null);
        dto.setStateSecretConfigured(!blank(row.getStateSecret()));
        return dto;
    }

    public SsoSettingsDto defaults(SecurityIdentityProperties properties) {
        var sso = properties.getSso();
        SsoSettingsDto dto = new SsoSettingsDto();
        dto.setEnabled(sso.isEnabled());
        dto.setProviderType(sso.getProviderType());
        dto.setLoginUrl(sso.getLoginUrl());
        dto.setAuthorizationUrl(sso.getAuthorizationUrl());
        dto.setTokenUrl(sso.getTokenUrl());
        dto.setUserInfoUrl(sso.getUserInfoUrl());
        dto.setClientId(sso.getClientId());
        dto.setClientSecret(null);
        dto.setClientSecretConfigured(!blank(sso.getClientSecret()));
        dto.setRedirectUri(sso.getRedirectUri());
        dto.setScopes(joinScopes(sso.getScopes()));
        dto.setTrustsIdpMfa(sso.isTrustsIdpMfa());
        dto.setMetadataUrl(sso.getMetadataUrl());
        dto.setIdpEntityId(sso.getIdpEntityId());
        dto.setSpEntityId(sso.getSpEntityId());
        dto.setAcsUrl(sso.getAcsUrl());
        dto.setX509Certificate(sso.getX509Certificate());
        dto.setSamlPrivateKey(null);
        dto.setSamlPrivateKeyConfigured(!blank(sso.getSamlPrivateKey()));
        dto.setAssertionSecret(null);
        dto.setAssertionSecretConfigured(!blank(sso.getAssertionSecret()));
        dto.setStateSecret(null);
        dto.setStateSecretConfigured(!blank(sso.getStateSecret()));
        return dto;
    }

    private String joinScopes(java.util.List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "openid profile email";
        }
        StringJoiner joiner = new StringJoiner(" ");
        scopes.forEach(joiner::add);
        return joiner.toString();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SsoSettingsDto {
    private boolean enabled;
    private String providerType = "OIDC";
    private String loginUrl;
    private String authorizationUrl;
    private String tokenUrl;
    private String userInfoUrl;
    private String clientId;
    private String clientSecret;
    private boolean clientSecretConfigured;
    private String redirectUri;
    private String scopes = "openid profile email";
    private boolean trustsIdpMfa = true;
    private String metadataUrl;
    private String idpEntityId;
    private String spEntityId;
    private String acsUrl;
    private String x509Certificate;
    private String samlPrivateKey;
    private boolean samlPrivateKeyConfigured;
    private String assertionSecret;
    private boolean assertionSecretConfigured;
    private String stateSecret;
    private boolean stateSecretConfigured;
}

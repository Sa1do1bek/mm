package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.security.SensitiveStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sso_settings", uniqueConstraints = @UniqueConstraint(name = "uk_sso_settings_tenant_company", columnNames = {"tenant_id", "company_id"}))
public class SsoSetting extends BusinessEntity {
    private boolean enabled = false;

    @Column(name = "provider_type", nullable = false, length = 20)
    private String providerType = "OIDC";

    @Column(name = "login_url", length = 500)
    private String loginUrl;

    @Column(name = "authorization_url", length = 500)
    private String authorizationUrl;

    @Column(name = "token_url", length = 500)
    private String tokenUrl;

    @Column(name = "user_info_url", length = 500)
    private String userInfoUrl;

    @Column(name = "client_id", length = 240)
    private String clientId;

    @Convert(converter = SensitiveStringConverter.class)
    @Column(name = "client_secret", columnDefinition = "text")
    private String clientSecret;

    @Column(name = "redirect_uri", length = 500)
    private String redirectUri;

    @Column(length = 500)
    private String scopes;

    @Column(name = "trusts_idp_mfa", nullable = false)
    private boolean trustsIdpMfa = true;

    @Column(name = "metadata_url", length = 500)
    private String metadataUrl;

    @Column(name = "idp_entity_id", length = 300)
    private String idpEntityId;

    @Column(name = "sp_entity_id", length = 300)
    private String spEntityId;

    @Column(name = "acs_url", length = 500)
    private String acsUrl;

    @Column(name = "x509_certificate", columnDefinition = "text")
    private String x509Certificate;

    @Convert(converter = SensitiveStringConverter.class)
    @Column(name = "saml_private_key", columnDefinition = "text")
    private String samlPrivateKey;

    @Convert(converter = SensitiveStringConverter.class)
    @Column(name = "assertion_secret", columnDefinition = "text")
    private String assertionSecret;

    @Convert(converter = SensitiveStringConverter.class)
    @Column(name = "state_secret", columnDefinition = "text")
    private String stateSecret;
}

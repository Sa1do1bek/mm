package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.security.SensitiveStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sms_gateway_settings", uniqueConstraints = @UniqueConstraint(name = "uk_sms_gateway_settings_tenant_company", columnNames = {"tenant_id", "company_id"}))
public class SmsGatewaySetting extends BusinessEntity {
    private boolean enabled = false;

    @Column(name = "mfa_enabled")
    private boolean mfaEnabled = false;

    @Column(name = "provider_code", nullable = false, length = 40)
    private String providerCode = "CUSTOM";

    @Column(length = 500)
    private String url;

    @Column(name = "auth_url", length = 500)
    private String authUrl;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod = "POST";

    @Column(name = "auth_header", length = 120)
    private String authHeader = "Authorization";

    @Convert(converter = SensitiveStringConverter.class)
    @Column(name = "auth_token", columnDefinition = "text")
    private String authToken;

    @Column(length = 180)
    private String login;

    @Convert(converter = SensitiveStringConverter.class)
    @Column(name = "password_secret", columnDefinition = "text")
    private String password;

    @Column(name = "account_sid", length = 180)
    private String accountSid;

    @Column(name = "service_sid", length = 180)
    private String serviceSid;

    @Column(name = "from_phone", length = 80)
    private String fromPhone;

    @Column(length = 120)
    private String sender;

    @Column(columnDefinition = "text")
    private String template = "ERP MM verification code: {code}. Valid for {ttlSeconds} seconds.";

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 8;

    @Column(name = "ttl_seconds", nullable = false)
    private int ttlSeconds = 300;

    @Column(name = "last_provider_status", length = 80)
    private String lastProviderStatus;

    @Column(name = "last_provider_message", columnDefinition = "text")
    private String lastProviderMessage;

    @Column(name = "last_tested_at")
    private LocalDateTime lastTestedAt;
}

package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "integration_settings", uniqueConstraints = @UniqueConstraint(name = "uk_integration_settings_tenant_company_system", columnNames = {"tenant_id", "company_id", "system_code"}))
public class IntegrationSetting extends BusinessEntity {
    @Column(name = "system_code", nullable = false, length = 60)
    private String systemCode;

    @Column(name = "provider_name", length = 120)
    private String providerName;

    private boolean enabled = true;
    private boolean mockMode = true;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "auth_type", length = 40)
    private String authType = "NONE";

    @Column(name = "username", length = 180)
    private String username;

    @Column(name = "password_encrypted", columnDefinition = "text")
    private String passwordEncrypted;

    @Column(name = "bearer_token_encrypted", columnDefinition = "text")
    private String bearerTokenEncrypted;

    @Column(name = "api_key_header", length = 120)
    private String apiKeyHeader;

    @Column(name = "api_key_encrypted", columnDefinition = "text")
    private String apiKeyEncrypted;

    @Column(name = "client_id", length = 180)
    private String clientId;

    @Column(name = "client_secret_encrypted", columnDefinition = "text")
    private String clientSecretEncrypted;

    @Column(name = "sender_tin", length = 40)
    private String senderTin;

    @Column(name = "sender_branch_code", length = 80)
    private String senderBranchCode;

    @Column(name = "callback_url", length = 500)
    private String callbackUrl;

    @Column(name = "auth_path", length = 240)
    private String authPath;

    @Column(name = "health_path", length = 240)
    private String healthPath = "/health";

    @Column(name = "events_path", length = 240)
    private String eventsPath = "/events";

    @Column(name = "invoice_path", length = 240)
    private String invoicePath;

    @Column(name = "document_path", length = 240)
    private String documentPath;

    @Column(name = "supplier_status_path", length = 240)
    private String supplierStatusPath;

    @Column(name = "provider_version", length = 80)
    private String providerVersion;

    @Column(name = "organization_id", length = 180)
    private String organizationId;

    @Column(name = "project_id", length = 180)
    private String projectId;

    @Column(name = "ai_model", length = 120)
    private String aiModel;

    @Column(name = "ai_request_path", length = 320)
    private String aiRequestPath;

    @Column(name = "ai_system_prompt", columnDefinition = "text")
    private String aiSystemPrompt;

    @Column(name = "ai_temperature", precision = 5, scale = 2)
    private BigDecimal aiTemperature = BigDecimal.valueOf(0.20);

    @Column(name = "ai_max_tokens")
    private Integer aiMaxTokens = 1024;

    @Column(name = "ai_timeout_seconds")
    private Integer aiTimeoutSeconds = 30;
}

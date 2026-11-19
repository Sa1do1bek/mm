package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IntegrationSettingCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String systemCode;
    private String providerName;
    private boolean enabled;
    private boolean mockMode;
    private String baseUrl;
    private String authType;
    private String username;
    private String password;
    private boolean passwordConfigured;
    private String bearerToken;
    private boolean bearerTokenConfigured;
    private String apiKeyHeader;
    private String apiKey;
    private boolean apiKeyConfigured;
    private String clientId;
    private String clientSecret;
    private boolean clientSecretConfigured;
    private String senderTin;
    private String senderBranchCode;
    private String callbackUrl;
    private String authPath;
    private String healthPath;
    private String eventsPath;
    private String invoicePath;
    private String documentPath;
    private String supplierStatusPath;
    private String providerVersion;
    private String organizationId;
    private String projectId;
    private String aiModel;
    private String aiRequestPath;
    private String aiSystemPrompt;
    private BigDecimal aiTemperature;
    private Integer aiMaxTokens;
    private Integer aiTimeoutSeconds;
}

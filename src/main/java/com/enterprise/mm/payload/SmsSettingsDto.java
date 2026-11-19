package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SmsSettingsDto {
    private boolean enabled;
    private boolean mfaEnabled;
    private int ttlSeconds = 300;
    private String providerCode = "CUSTOM";
    private String url;
    private String authUrl;
    private String httpMethod = "POST";
    private String authHeader = "Authorization";
    private String authToken;
    private boolean authTokenConfigured;
    private String login;
    private String password;
    private boolean passwordConfigured;
    private String accountSid;
    private String serviceSid;
    private String fromPhone;
    private String sender;
    private String template = "ERP MM verification code: {code}. Valid for {ttlSeconds} seconds.";
    private int timeoutSeconds = 8;
    private String lastProviderStatus;
    private String lastProviderMessage;
    private LocalDateTime lastTestedAt;
}

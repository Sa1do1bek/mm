package com.enterprise.mm.config;

import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.identity")
public class SecurityIdentityProperties {
    private Sso sso = new Sso();
    private SmsMfa smsMfa = new SmsMfa();

    @Getter
    @Setter
    public static class Sso {
        private boolean enabled;
        private String providerType = "OIDC";
        private String loginUrl;
        private String assertionSecret;
        private String stateSecret;
        private String authorizationUrl;
        private String tokenUrl;
        private String userInfoUrl;
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        private String clientAuthMethod = "BASIC";
        private List<String> scopes = List.of("openid", "profile", "email");
        private boolean trustsIdpMfa = true;
        private long timestampSkewSeconds = 300;
        private List<String> allowedRoles = List.of("ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "ACCOUNTANT", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "INTEGRATION_OPERATOR", "SUPPLIER_MANAGER", "EXECUTIVE", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
        private String metadataUrl;
        private String idpEntityId;
        private String spEntityId;
        private String acsUrl;
        private String x509Certificate;
        private String samlPrivateKey;

    }

    @Getter
    @Setter
    public static class SmsMfa {
        private boolean enabled;
        private String codePepper = "change-this-sms-mfa-pepper";
        private int ttlSeconds = 300;
        private boolean includeCodeInOutbox;
        private Gateway gateway = new Gateway();

        @Getter
        @Setter
        public static class Gateway {
            private boolean enabled;
            private String providerCode = "CUSTOM";
            private String url;
            private String authUrl;
            private String method = "POST";
            private String authHeader = "Authorization";
            private String authToken;
            private String login;
            private String password;
            private String accountSid;
            private String serviceSid;
            private String fromPhone;
            private String sender;
            private String template = "ERP MM verification code: {code}. Valid for {ttlSeconds} seconds.";
            private int timeoutSeconds = 8;
            private Map<String, String> extraHeaders = Map.of();
        }
    }
}

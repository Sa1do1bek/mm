package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.payload.MobileWarehouseDtos.MobileSignatureRequest;
import com.enterprise.mm.service.EImzoVerificationService;
import com.enterprise.mm.service.SmsMfaService;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MobileWarehouseOperation;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.identity.sso.enabled=true",
    "app.identity.sso.provider-type=OIDC",
    "app.identity.sso.login-url=https://idp.example.test/login",
    "app.identity.sso.authorization-url=https://idp.example.test/oauth2/authorize",
    "app.identity.sso.client-id=mm-client",
    "app.identity.sso.redirect-uri=http://localhost:5173/sso/callback",
    "app.identity.sso.assertion-secret=test-sso-secret",
    "app.identity.sms-mfa.enabled=true"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class SsoIdentityTest {
    private static final AtomicReference<String> SMS_BODY = new AtomicReference<>();
    private static final AtomicReference<String> EIMZO_BODY = new AtomicReference<>();
    private static final HttpServer PROVIDER = startProvider();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SmsMfaService smsMfaService;
    @Autowired EImzoVerificationService eImzoVerificationService;
    @Autowired UserRepository userRepository;

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.identity.sso.authorization-url", () -> providerBaseUrl() + "/oidc/authorize");
        registry.add("app.identity.sso.token-url", () -> providerBaseUrl() + "/oidc/token");
        registry.add("app.identity.sso.user-info-url", () -> providerBaseUrl() + "/oidc/userinfo");
        registry.add("app.identity.sms-mfa.gateway.enabled", () -> "true");
        registry.add("app.identity.sms-mfa.gateway.url", () -> providerBaseUrl() + "/sms/send");
        registry.add("app.integrations.systems.e-imzo.mock-mode", () -> "false");
        registry.add("app.integrations.systems.e-imzo.base-url", SsoIdentityTest::providerBaseUrl);
        registry.add("app.integrations.systems.e-imzo.events-path", () -> "/api/signature/verify");
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop(0);
    }

    @Test
    void securityOptionsExposeSsoAndSmsMfa() throws Exception {
        String response = mockMvc.perform(get("/api/auth/security-options").header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("ssoEnabled").asBoolean()).isTrue();
        assertThat(data.path("ssoProviderType").asText()).isEqualTo("OIDC");
        assertThat(data.path("ssoLoginUrl").asText()).isEqualTo("https://idp.example.test/login");
        assertThat(data.path("smsMfaEnabled").asBoolean()).isTrue();
    }

    @Test
    void oidcLoginUrlContainsStateAndClientParameters() throws Exception {
        String response = mockMvc.perform(get("/api/auth/sso/login-url")
                .header("X-Tenant-Id", "default")
                .param("tenantId", "default")
                .param("companyId", "00000000-0000-0000-0000-000000000001"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("authorizationUrl").asText()).contains(providerBaseUrl() + "/oidc/authorize");
        assertThat(data.path("authorizationUrl").asText()).contains("response_type=code");
        assertThat(data.path("authorizationUrl").asText()).contains("client_id=mm-client");
        assertThat(data.path("authorizationUrl").asText()).contains("state=");
        assertThat(data.path("state").asText()).isNotBlank();
        assertThat(data.path("expiresAtEpochSeconds").asLong()).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void signedSsoAssertionCreatesJwt() throws Exception {
        long issuedAt = Instant.now().getEpochSecond();
        String nonce = "nonce-" + issuedAt;
        String canonical = String.join("|",
            "default",
            "00000000-0000-0000-0000-000000000001",
            "sso-user",
            "sso-user@example.test",
            "oidc-subject-1",
            "VIEWER",
            Long.toString(issuedAt),
            nonce
        );
        String signature = sign(canonical);

        String response = mockMvc.perform(post("/api/auth/sso/assertion")
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "tenantId":"default",
                      "companyId":"00000000-0000-0000-0000-000000000001",
                      "username":"sso-user",
                      "email":"sso-user@example.test",
                      "fullName":"SSO User",
                      "externalSubject":"oidc-subject-1",
                      "roles":["VIEWER"],
                      "issuedAtEpochSeconds":%d,
                      "nonce":"%s",
                      "signature":"%s"
                    }
                    """.formatted(issuedAt, nonce, signature)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("username").asText()).isEqualTo("sso-user");
        assertThat(data.path("roles").toString()).contains("ROLE_VIEWER");
    }

    @Test
    void oidcCallbackExchangesCodeWithProviderAndCreatesJwt() throws Exception {
        String loginResponse = mockMvc.perform(get("/api/auth/sso/login-url")
                .header("X-Tenant-Id", "default")
                .param("tenantId", "default")
                .param("companyId", "00000000-0000-0000-0000-000000000001"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        String state = objectMapper.readTree(loginResponse).path("data").path("state").asText();

        String response = mockMvc.perform(post("/api/auth/sso/oidc/callback")
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"mock-code",
                      "state":"%s"
                    }
                    """.formatted(state)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("username").asText()).isEqualTo("oidc-live-user");
        assertThat(data.path("roles").toString()).contains("ROLE_MM_MANAGER");
    }

    @Test
    void smsMfaUsesConfiguredGateway() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        User admin = userRepository
            .findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, "admin")
            .orElseThrow();
        admin.setMfaPhoneNumber("+998 90 123-45-67");
        admin = userRepository.saveAndFlush(admin);
        SMS_BODY.set(null);

        smsMfaService.requestChallenge(admin, "LOGIN");

        assertThat(SMS_BODY.get())
            .contains("\"phoneNumber\":\"+998 90 123-45-67\"")
            .contains("ERP MM verification code")
            .contains("\"purpose\":\"LOGIN\"");
    }

    @Test
    void smsRetryAndDeliveryCallbackApisAreAvailable() throws Exception {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        User admin = userRepository
            .findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse("default", CompanyContext.DEFAULT_COMPANY_ID, "admin")
            .orElseThrow();
        admin.setMfaPhoneNumber("+998 90 123-45-67");
        userRepository.saveAndFlush(admin);
        SMS_BODY.set(null);

        mockMvc.perform(post("/api/mfa/sms/retry")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", "default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"purpose":"LOGIN_RETRY"}
                    """))
            .andExpect(status().isOk());

        assertThat(SMS_BODY.get())
            .contains("\"phoneNumber\":\"+998 90 123-45-67\"")
            .contains("\"purpose\":\"LOGIN_RETRY\"");

        String callback = mockMvc.perform(post("/api/mfa/sms/delivery-callback")
                .with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", "sms-delivery-callback-api-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "messageId":"MSG-100",
                      "username":"admin",
                      "phoneNumber":"+998 90 123-45-67",
                      "status":"DELIVERED",
                      "providerStatus":"delivered"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode data = objectMapper.readTree(callback).path("data");
        assertThat(data.path("integrationEventId").asText()).isNotBlank();
        assertThat(data.path("message").asText()).contains("accepted");
    }

    @Test
    void smsSettingsSupportPlaymobileEskizAndTwilioProviders() throws Exception {
        SMS_BODY.set(null);
        saveSmsSettings("sms-playmobile-test", """
            {
              "enabled":true,
              "mfaEnabled":true,
              "providerCode":"PLAYMOBILE",
              "url":"%s/broker-api/send",
              "login":"playmobile-user",
              "password":"playmobile-secret",
              "sender":"ERP-MM",
              "ttlSeconds":300,
              "timeoutSeconds":5
            }
            """.formatted(providerBaseUrl()));
        testSms("sms-playmobile-test");
        assertThat(SMS_BODY.get()).contains("\"messages\"").contains("\"recipient\":\"998901234567\"");

        SMS_BODY.set(null);
        String eskiz = saveSmsSettings("sms-eskiz-test", """
            {
              "enabled":true,
              "mfaEnabled":true,
              "providerCode":"ESKIZ",
              "authUrl":"%s/api/auth/login",
              "url":"%s/api/message/sms/send",
              "login":"eskiz@example.uz",
              "password":"eskiz-secret",
              "sender":"4546",
              "ttlSeconds":300,
              "timeoutSeconds":5
            }
            """.formatted(providerBaseUrl(), providerBaseUrl()));
        JsonNode eskizData = objectMapper.readTree(eskiz).path("data");
        assertThat(eskizData.path("passwordConfigured").asBoolean()).isTrue();
        assertThat(eskizData.path("password").isMissingNode() || eskizData.path("password").isNull()).isTrue();
        testSms("sms-eskiz-test");
        assertThat(SMS_BODY.get()).contains("mobile_phone=998901234567").contains("message=");

        SMS_BODY.set(null);
        String twilio = saveSmsSettings("sms-twilio-test", """
            {
              "enabled":true,
              "mfaEnabled":true,
              "providerCode":"TWILIO",
              "url":"%s/2010-04-01/Accounts/{accountSid}/Messages.json",
              "accountSid":"AC00000000000000000000000000000000",
              "authToken":"twilio-secret",
              "fromPhone":"+12025550100",
              "ttlSeconds":300,
              "timeoutSeconds":5
            }
            """.formatted(providerBaseUrl()));
        JsonNode twilioData = objectMapper.readTree(twilio).path("data");
        assertThat(twilioData.path("authTokenConfigured").asBoolean()).isTrue();
        assertThat(twilioData.path("authToken").isMissingNode() || twilioData.path("authToken").isNull()).isTrue();
        testSms("sms-twilio-test");
        assertThat(SMS_BODY.get()).contains("To=%2B998901234567").contains("Body=");
    }

    @Test
    void eimzoVerificationUsesConfiguredProvider() {
        MobileWarehouseOperation operation = new MobileWarehouseOperation();
        operation.setCode("MWO-TEST");
        operation.setName("Mobile signature test");
        operation.setOperationType(com.enterprise.mm.enums.MobileWarehouseOperationType.RECEIVE);
        EIMZO_BODY.set(null);

        eImzoVerificationService.verify(
            operation,
            new MobileSignatureRequest("payload-to-sign", "pkcs7-signature", "AA001122", "123456789")
        );

        assertThat(EIMZO_BODY.get())
            .contains("\"operationType\":\"RECEIVE\"")
            .contains("\"pkcs7\":\"pkcs7-signature\"")
            .contains("\"signerTin\":\"123456789\"");
    }

    @Test
    void eimzoDocumentSignatureApisUseConfiguredProvider() throws Exception {
        UUID entityId = UUID.randomUUID();
        EIMZO_BODY.set(null);

        String response = mockMvc.perform(post("/api/integrations/e-imzo/documents/verify")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "entityType":"CONTRACT",
                      "entityId":"%s",
                      "signaturePayload":"payload-to-sign",
                      "pkcs7":"pkcs7-signature",
                      "certificateSerial":"AA001122",
                      "signerTin":"307123456"
                    }
                    """.formatted(entityId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(objectMapper.readTree(response).path("data").path("verified").asBoolean()).isTrue();
        assertThat(EIMZO_BODY.get())
            .contains("\"entityType\":\"CONTRACT\"")
            .contains("\"entityId\":\"" + entityId + "\"")
            .contains("\"pkcs7\":\"pkcs7-signature\"");
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-sso-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpServer startProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/oidc/token", exchange -> json(exchange, 200, """
                {"access_token":"mock-access-token","token_type":"Bearer","expires_in":3600}
                """));
            server.createContext("/oidc/userinfo", exchange -> json(exchange, 200, """
                {
                  "sub":"mock-subject",
                  "preferred_username":"oidc-live-user",
                  "email":"oidc-live-user@example.uz",
                  "name":"OIDC Live User",
                  "roles":["VIEWER","MM_MANAGER"]
                }
                """));
            server.createContext("/sms/send", exchange -> {
                SMS_BODY.set(body(exchange));
                json(exchange, 202, "{\"success\":true}");
            });
            server.createContext("/broker-api/send", exchange -> {
                SMS_BODY.set(body(exchange));
                json(exchange, 202, "{\"success\":true,\"provider\":\"playmobile\"}");
            });
            server.createContext("/api/auth/login", exchange -> json(exchange, 200, """
                {"success":true,"data":{"token":"mock-eskiz-token"}}
                """));
            server.createContext("/api/message/sms/send", exchange -> {
                SMS_BODY.set(body(exchange));
                json(exchange, 202, "{\"success\":true,\"provider\":\"eskiz\"}");
            });
            server.createContext("/2010-04-01", exchange -> {
                SMS_BODY.set(body(exchange));
                json(exchange, 201, "{\"sid\":\"SM00000000000000000000000000000000\",\"status\":\"queued\"}");
            });
            server.createContext("/api/signature/verify", exchange -> {
                EIMZO_BODY.set(body(exchange));
                json(exchange, 200, "{\"verified\":true}");
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot start identity test provider", ex);
        }
    }

    private static String providerBaseUrl() {
        return "http://localhost:" + PROVIDER.getAddress().getPort();
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String saveSmsSettings(String tenantId, String payload) throws Exception {
        return mockMvc.perform(put("/api/settings/sms")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    private void testSms(String tenantId) throws Exception {
        mockMvc.perform(post("/api/settings/sms/test")
                .with(user("admin").roles("ADMIN"))
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"phoneNumber":"+998901234567"}
                    """))
            .andExpect(status().isOk());
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

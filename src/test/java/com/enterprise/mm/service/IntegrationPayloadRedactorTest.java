package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class IntegrationPayloadRedactorTest {
    private final IntegrationPayloadRedactor redactor = new IntegrationPayloadRedactor(new ObjectMapper());

    @Test
    void recursivelyRedactsCredentialsAndRemovesUrlCredentialsAndQuery() {
        var result = redactor.redactPayload("""
            {
              "purchaseOrderId":"00000000-0000-0000-0000-000000000123",
              "authorization":"Bearer top-secret",
              "nested":{
                "client_secret":"secret-value",
                "apiKey":"api-value",
                "callbackUrl":"https://user:password@example.test/callback?token=query-secret"
              },
              "items":[{"password":"p1"},{"signature":"sig"}]
            }
            """);

        assertThat(result.path("purchaseOrderId").asText()).isEqualTo("00000000-0000-0000-0000-000000000123");
        assertThat(result.path("authorization").asText()).isEqualTo("***REDACTED***");
        assertThat(result.path("nested").path("client_secret").asText()).isEqualTo("***REDACTED***");
        assertThat(result.path("nested").path("apiKey").asText()).isEqualTo("***REDACTED***");
        assertThat(result.path("nested").path("callbackUrl").asText()).isEqualTo("https://example.test/callback");
        assertThat(result.path("items").get(0).path("password").asText()).isEqualTo("***REDACTED***");
        assertThat(result.toString()).doesNotContain("top-secret", "secret-value", "api-value", "query-secret", "password@example");
    }

    @Test
    void invalidPayloadProvidesBoundedSanitizedMetadataInsteadOfRawSecret() {
        var result = redactor.redactPayload("password=do-not-show malformed payload");

        assertThat(result.path("format").asText()).isEqualTo("UNPARSEABLE");
        assertThat(result.path("preview").asText()).contains("password=***REDACTED***");
        assertThat(result.toString()).doesNotContain("do-not-show");
    }
}

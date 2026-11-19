package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class ReceiptAdviceHttpClientTest {
    ObjectMapper mapper = new ObjectMapper();
    UUID company = UUID.randomUUID();
    HttpMmAiClient client;
    MockRestServiceServer server;
    @BeforeEach void setup() {
        TenantContext.setTenant("receipt-tenant");
        CompanyContext.setCompany(company);
        MDC.put("requestId", "receipt-request-1");
        client = new HttpMmAiClient(new RestTemplateBuilder(), "https://ai.example/", "test-key",
            Duration.ofSeconds(5), Duration.ofSeconds(180));
        server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(client, "receiptRestTemplate")).build();
    }
    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); MDC.clear(); }
    JsonNode fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/receipt-advice/receipt-suggestions-mm-mixed-request.json")) {
            return mapper.readTree(in).get("backendContext");
        }
    }
    @Test void postsActualMixedContextWithTrustedScopeHeadersAndNoProviderOrWrapper() throws Exception {
        JsonNode context = fixture();
        server.expect(requestTo("https://ai.example/v1/receipt-advice/suggest"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-Key", "test-key"))
            .andExpect(header("X-Tenant-Id", "receipt-tenant"))
            .andExpect(header("X-Request-Id", "receipt-request-1"))
            .andExpect(jsonPath("$.companyId").value(company.toString()))
            .andExpect(jsonPath("$.tenantId").value("receipt-tenant"))
            .andExpect(jsonPath("$.language").value("uz"))
            .andExpect(jsonPath("$.provider").doesNotExist())
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.backendContext.positions.content[0].kind").value("MATERIAL_GROUP"))
            .andExpect(jsonPath("$.backendContext.positions.content[1].materials[0].receiptSelection.decisionSource").value("CONTRACT_READINESS"))
            .andRespond(withSuccess("{\"status\":\"READY\"}", MediaType.APPLICATION_JSON));
        assertThat(client.suggestReceiptAdvice(context, "uz").path("status").asText()).isEqualTo("READY");
        server.verify();
    }
    @Test void parsesUpstreamErrorEnvelopeAndRetryAfterWithoutRetrying() throws Exception {
        server.expect(requestTo("https://ai.example/v1/receipt-advice/suggest"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"errorCode\":\"RECEIPT_ADVICE_BUSY\",\"message\":\"busy\",\"requestId\":\"nested-id\"}}"));
        assertThatThrownBy(() -> client.suggestReceiptAdvice(fixture(), "ru"))
            .isInstanceOfSatisfying(MmAiServiceException.class, e -> {
                assertThat(e.httpStatus()).isEqualTo(503);
                assertThat(e.errorCode()).isEqualTo("RECEIPT_ADVICE_BUSY");
                assertThat(e.upstreamRequestId()).isEqualTo("nested-id");
                assertThat(e.retryAfterSeconds()).isEqualTo(5);
            });
        server.verify();
    }
    @Test void authenticationAndValidationErrorsAreTyped() throws Exception {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND,
            HttpStatus.PAYLOAD_TOO_LARGE, HttpStatus.UNPROCESSABLE_ENTITY}) {
            server.reset();
            server.expect(requestTo("https://ai.example/v1/receipt-advice/suggest"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"errorCode\":\"VALIDATION_ERROR\",\"message\":\"upstream\"}}"));
            assertThatThrownBy(() -> client.suggestReceiptAdvice(fixture(), "en"))
                .isInstanceOfSatisfying(MmAiServiceException.class, e -> assertThat(e.httpStatus()).isEqualTo(status.value()));
            server.verify();
        }
    }
    @Test void missingKeyFailsClosedBeforeNetwork() throws Exception {
        var unconfigured = new HttpMmAiClient(new RestTemplateBuilder(), "https://ai.example", "",
            Duration.ofSeconds(5), Duration.ofSeconds(180));
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.suggestReceiptAdvice(fixture(), "uz"))
            .hasMessageContaining("not configured");
    }
    @Test void socketTimeoutDoesNotRetry() throws Exception {
        server.expect(requestTo("https://ai.example/v1/receipt-advice/suggest"))
            .andRespond(request -> { throw new SocketTimeoutException("test timeout"); });
        assertThatThrownBy(() -> client.suggestReceiptAdvice(fixture(), "uz"))
            .isInstanceOf(ResourceAccessException.class);
        server.verify();
    }
    @Test void createsRequestIdWhenThereIsNoTracingContext() throws Exception {
        MDC.clear();
        server.expect(requestTo("https://ai.example/v1/receipt-advice/suggest"))
            .andExpect(request -> assertThat(UUID.fromString(request.getHeaders().getFirst("X-Request-Id"))).isNotNull())
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.suggestReceiptAdvice(fixture(), "en");
        server.verify();
    }
}

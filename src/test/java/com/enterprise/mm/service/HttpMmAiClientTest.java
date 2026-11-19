package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;

class HttpMmAiClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void stockAccountRecommendationUsesAgreedRouteAndTenantHeader() throws Exception {
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/stock-account-recommendation/recommend", exchange -> {
            tenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"NO_RELIABLE_SUGGESTION\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(UUID.randomUUID());
        try {
            HttpMmAiClient client = new HttpMmAiClient(new RestTemplateBuilder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret-key",
                Duration.ofSeconds(5), Duration.ofSeconds(30));
            var context = objectMapper.createObjectNode().put("schemaVersion", 1);

            assertThat(client.recommendMaterialStockAccount(context, "uz").path("status").asText())
                .isEqualTo("NO_RELIABLE_SUGGESTION");
            assertThat(tenant.get()).isEqualTo("tenant-a");
            assertThat(objectMapper.readTree(body.get())).isEqualTo(context);
        } finally {
            TenantContext.clear();
            CompanyContext.clear();
            server.stop(0);
        }
    }

    @Test
    void compareRfqQuotationsPostsRfqFieldAndApiKey() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/quotation-comparison/from-rfq", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"recommendation\":\"COMPARABLE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            UUID rfqId = UUID.fromString("1986b1ac-131a-4b7a-8c17-bb78a19f77bc");
            HttpMmAiClient client = new HttpMmAiClient(
                new RestTemplateBuilder(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                "secret-key",
                Duration.ofSeconds(5),
                Duration.ofSeconds(30)
            );

            client.compareRfqQuotations(rfqId, "uz");

            assertThat(path.get()).isEqualTo("/v1/quotation-comparison/from-rfq");
            assertThat(apiKey.get()).isEqualTo("secret-key");
            assertThat(objectMapper.readTree(body.get()))
                .isEqualTo(objectMapper.readTree("{\"rfq\":\"1986b1ac-131a-4b7a-8c17-bb78a19f77bc\"}"));
        } finally {
            server.stop(0);
        }
    }
}

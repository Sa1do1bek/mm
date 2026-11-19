package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.service.InventoryAiDraftService.FileSnapshot;
import com.enterprise.mm.service.InventoryAiDraftService.SourceFile;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

class InventoryAiHttpClientTest {
    @Test
    void sendsBothFilesAndMetadataThroughMmAiBoundary() throws Exception {
        UUID company = UUID.randomUUID();
        UUID analysis = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> companyHeader = new AtomicReference<>();
        AtomicReference<String> pollTenant = new AtomicReference<>();
        AtomicReference<String> pollCompany = new AtomicReference<>();
        AtomicReference<String> pollKey = new AtomicReference<>();
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/inventory-document-extraction/jobs", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                int pollNumber = polls.incrementAndGet();
                pollTenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
                pollCompany.set(exchange.getRequestHeaders().getFirst("X-Company-Id"));
                pollKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
                if (pollNumber == 1) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                byte[] response = ("{\"analysisId\":\"" + analysis + "\",\"status\":\"COMPLETED\","
                    + "\"statusUrl\":\"/v1/inventory-document-extraction/jobs/" + analysis + "\","
                    + "\"result\":{\"analysisId\":\"" + analysis + "\",\"status\":\"NEEDS_REVIEW\"}}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            uploads.incrementAndGet();
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            key.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            tenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            companyHeader.set(exchange.getRequestHeaders().getFirst("X-Company-Id"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] response = ("{\"analysisId\":\"" + analysis + "\",\"status\":\"QUEUED\","
                + "\"statusUrl\":\"/v1/inventory-document-extraction/jobs/" + analysis + "\","
                + "\"pollAfterSeconds\":0}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            TenantContext.setTenant("tenant-a");
            CompanyContext.setCompany(company);
            HttpMmAiClient client = new HttpMmAiClient(new RestTemplateBuilder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret-key",
                Duration.ofSeconds(2), Duration.ofSeconds(10));
            var files = List.of(
                new SourceFile(new FileSnapshot(first, "first.pdf", "application/pdf", "hash-1"), "PDF one".getBytes(StandardCharsets.UTF_8)),
                new SourceFile(new FileSnapshot(second, "second.pdf", "application/pdf", "hash-2"), "PDF two".getBytes(StandardCharsets.UTF_8)));
            assertThat(client.extractInventoryDocument(analysis, files, "uz").path("status").asText()).isEqualTo("NEEDS_REVIEW");
            assertThat(contentType.get()).startsWith("multipart/form-data");
            assertThat(key.get()).isEqualTo("secret-key");
            assertThat(tenant.get()).isEqualTo("tenant-a");
            assertThat(companyHeader.get()).isEqualTo(company.toString());
            assertThat(pollTenant.get()).isEqualTo("tenant-a");
            assertThat(pollCompany.get()).isEqualTo(company.toString());
            assertThat(pollKey.get()).isEqualTo("secret-key");
            assertThat(uploads.get()).isEqualTo(1);
            assertThat(polls.get()).isEqualTo(2);
            assertThat(body.get()).contains(analysis.toString(), first.toString(), second.toString(), "first.pdf", "second.pdf", "PDF one", "PDF two");
            assertThat(body.get()).contains("name=\"metadata\"").contains("Content-Type: text/plain");
            assertThat(body.get()).doesNotContain("name=\"metadata\"; filename=");
            client.extractInventoryDocument(analysis, files, "uz", 1);
            assertThat(body.get()).contains("\"commissionAnalysisVersion\":1");
        } finally {
            TenantContext.clear();
            CompanyContext.clear();
            server.stop(0);
        }
    }

    @Test
    void preservesVersionTwoPollUrlAndSurfacesUpstreamFailure() throws Exception {
        UUID company = UUID.randomUUID();
        UUID analysis = UUID.randomUUID();
        UUID department = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        AtomicReference<String> metadata = new AtomicReference<>();
        AtomicReference<String> pollQuery = new AtomicReference<>();
        AtomicReference<String> pollTenant = new AtomicReference<>();
        AtomicReference<String> pollCompany = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/inventory-document-extraction/jobs", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] response;
            if ("POST".equals(exchange.getRequestMethod())) {
                metadata.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                response = ("{\"analysisId\":\"" + analysis + "\",\"status\":\"WAITING\","
                    + "\"statusUrl\":\"/v1/inventory-document-extraction/jobs/" + analysis
                    + "?analysisVersion=2\",\"pollAfterSeconds\":0}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(202, response.length);
            } else {
                pollQuery.set(exchange.getRequestURI().getRawQuery());
                pollTenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
                pollCompany.set(exchange.getRequestHeaders().getFirst("X-Company-Id"));
                response = ("{\"analysisId\":\"" + analysis + "\",\"status\":\"FAILED\","
                    + "\"statusUrl\":\"/v1/inventory-document-extraction/jobs/" + analysis
                    + "?analysisVersion=2\",\"error\":{\"errorCode\":\"LLM_BAD_OUTPUT\","
                    + "\"message\":\"Model response invalid\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
            }
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            TenantContext.setTenant("tenant-a");
            CompanyContext.setCompany(company);
            HttpMmAiClient client = new HttpMmAiClient(new RestTemplateBuilder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret-key",
                Duration.ofSeconds(2), Duration.ofSeconds(10));
            var scope = new InventoryOrderAnalysisScopeService.Scope("DEPARTMENTS", company,
                List.of(department), List.of(new InventoryOrderAnalysisScopeService.Target(
                    department, "001-sex", List.of(), List.of())));
            var files = List.of(new SourceFile(new FileSnapshot(fileId, "order.pdf", "application/pdf", "hash"),
                "PDF".getBytes(StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> client.extractInventoryDocument(analysis, files, "uz", 2, 2, scope))
                .isInstanceOf(MmAiServiceException.class)
                .hasMessageContaining("Model response invalid");
            assertThat(metadata.get()).contains("\"commissionAnalysisVersion\":2", "\"orderAnalysisVersion\":2",
                department.toString());
            assertThat(pollQuery.get()).isEqualTo("analysisVersion=2");
            assertThat(pollTenant.get()).isEqualTo("tenant-a");
            assertThat(pollCompany.get()).isEqualTo(company.toString());
        } finally {
            TenantContext.clear();
            CompanyContext.clear();
            server.stop(0);
        }
    }
}

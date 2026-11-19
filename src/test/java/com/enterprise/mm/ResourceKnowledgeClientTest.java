package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.service.ResourceKnowledgeClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

class ResourceKnowledgeClientTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void indexesAnImmutableResourceVersionWithScopedHeadersAndContractMetadata() throws Exception {
        UUID job = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> companyHeader = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/resource-knowledge/versions/index", exchange -> {
            tenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            companyHeader.set(exchange.getRequestHeaders().getFirst("X-Company-Id"));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("""
                {"jobId":"%s","tenantId":"tenant-a","companyId":"%s","resourceId":"%s",
                 "versionId":"%s","status":"QUEUED","indexGeneration":1,"sectionCount":0,"chunkCount":0}
                """.formatted(job, company, resource, version)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ResourceKnowledgeClient client = new ResourceKnowledgeClient(new RestTemplateBuilder(), json,
            "http://127.0.0.1:" + server.getAddress().getPort(), "service-key");
        JsonNode response = client.index(new ResourceKnowledgeClient.IndexMetadata(job, "tenant-a", company,
            resource, version, "Inventory order", "3", "order.txt", "text/plain",
            "a".repeat(64), "uz", 12, 1), "finish by 20 November".getBytes(StandardCharsets.UTF_8));

        assertThat(tenant.get()).isEqualTo("tenant-a");
        assertThat(companyHeader.get()).isEqualTo(company.toString());
        assertThat(apiKey.get()).isEqualTo("service-key");
        assertThat(contentType.get()).startsWith("multipart/form-data;");
        assertThat(body.get())
            .contains("name=\"metadata\"", "\"jobId\":\"" + job + "\"", "\"knowledgeRevision\":12",
                "\"indexGeneration\":1", "name=\"file\"", "finish by 20 November");
        assertThat(response.path("status").asText()).isEqualTo("QUEUED");
    }
}

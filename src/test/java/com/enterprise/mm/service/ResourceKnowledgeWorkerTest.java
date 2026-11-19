package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ResourceKnowledgeWorkerTest {
    private HikariDataSource source;
    private JdbcTemplate db;
    private HttpServer server;

    @BeforeEach
    void setup() {
        source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL");
        source.setUsername("sa");
        source.setPassword("");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE resources(id UUID PRIMARY KEY, status VARCHAR(20))");
        db.execute("""
            CREATE TABLE resource_versions(id UUID PRIMARY KEY, resource_id UUID, tenant_id VARCHAR(100), company_id UUID,
                index_generation INT, title VARCHAR(200), version_no INT, original_file_name VARCHAR(255),
                content_type VARCHAR(100), sha256 VARCHAR(64), status VARCHAR(20), index_status VARCHAR(20), index_error_code VARCHAR(100))
            """);
        db.execute("""
            CREATE TABLE resource_knowledge_events(id UUID PRIMARY KEY, tenant_id VARCHAR(100), company_id UUID,
                resource_id UUID, version_id UUID, kind VARCHAR(20), index_generation INT, knowledge_revision BIGINT,
                visibility_state VARCHAR(20), status VARCHAR(20), submitted BOOLEAN, attempts INT, error_code VARCHAR(100),
                next_attempt_at TIMESTAMP, lease_token UUID, lease_until TIMESTAMP, created_at TIMESTAMP)
            """);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (source != null) source.close();
    }

    @Test
    void marksVersionReadyOnlyAfterTheAiStatusConfirmsTheMatchingJob() throws Exception {
        UUID event = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        AtomicReference<String> tenantHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/resource-knowledge/jobs/" + event, exchange -> {
            tenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            byte[] response = ("""
                {"jobId":"%s","tenantId":"tenant-a","companyId":"%s","resourceId":"%s",
                 "versionId":"%s","status":"READY","indexGeneration":1}
                """.formatted(event, company, resource, version)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        db.update("INSERT INTO resources VALUES (?,?)", resource, "PUBLISHED");
        db.update("""
            INSERT INTO resource_versions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, version, resource, "tenant-a", company, 1, "Inventory order", 1, "order.txt", "text/plain",
            "a".repeat(64), "PUBLISHED", "PENDING", null);
        db.update("""
            INSERT INTO resource_knowledge_events VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, event, "tenant-a", company, resource, version, "INDEX", 1, 7L, "CURRENT", "PENDING", true, 0,
            null, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(1)), null, null,
            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
        ResourceKnowledgeClient client = new ResourceKnowledgeClient(new RestTemplateBuilder(), new ObjectMapper(),
            "http://127.0.0.1:" + server.getAddress().getPort(), "service-key");
        ResourceKnowledgeWorker worker = new ResourceKnowledgeWorker(db, client, mock(AttachmentService.class),
            new DataSourceTransactionManager(source), "uz");

        assertThat(worker.processNext()).isTrue();

        assertThat(tenantHeader.get()).isEqualTo("tenant-a");
        assertThat(db.queryForObject("SELECT status FROM resource_knowledge_events WHERE id=?", String.class, event)).isEqualTo("DONE");
        assertThat(db.queryForObject("SELECT index_status FROM resource_versions WHERE id=?", String.class, version)).isEqualTo("READY");
    }
}

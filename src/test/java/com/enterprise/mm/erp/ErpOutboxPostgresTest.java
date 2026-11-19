package com.enterprise.mm.erp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ErpOutboxPostgresTest {
    @Test @EnabledIfEnvironmentVariable(named="ERP_TEST_POSTGRES_URL", matches=".+")
    void captureRollsBackWithPostingAndKeepsExactEvidenceOnCommit() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("ERP_TEST_POSTGRES_URL"), "mm_local", "");
        var jdbc = new JdbcTemplate(ds); var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var mapper = new ObjectMapper(); var service = new ErpExchangeService(jdbc, mapper, mock(MaterialRepository.class), mock(MaterialGroupRepository.class), mock(com.enterprise.mm.service.RoleDataScopeService.class));
        String tenant = "erp-test-" + UUID.randomUUID(); UUID company = UUID.randomUUID(), document = UUID.randomUUID();
        UUID material = UUID.randomUUID(), warehouse = UUID.randomUUID();
        TenantContext.setTenant(tenant); CompanyContext.setCompany(company);
        try {
            jdbc.update("insert into erp_exchange_connection (id,tenant_id,company_id,enabled) values (?,?,?,true)", UUID.randomUUID(), tenant, company);
            var line = new ErpExchangeService.Line(material, warehouse, new BigDecimal("1.1234567"), new BigDecimal("31.0042"));
            tx.executeWithoutResult(status -> { service.acceptWarehouseDocument("ISSUE", document, 3, LocalDate.of(2025, 2, 3), "ISSUE", warehouse, null, List.of(line)); status.setRollbackOnly(); });
            assertThat(jdbc.queryForObject("select count(*) from erp_exchange_envelope where tenant_id=?", Integer.class, tenant)).isZero();
            tx.executeWithoutResult(status -> service.acceptWarehouseDocument("ISSUE", document, 3, LocalDate.of(2025, 2, 3), "ISSUE", warehouse, null, List.of(line)));
            var rows = jdbc.queryForList("select * from erp_exchange_envelope where tenant_id=?", tenant);
            assertThat(rows).hasSize(1); var row=rows.get(0); var payload=mapper.readTree((String)row.get("payload"));
            assertThat(row.get("delivery_state")).isEqualTo("PENDING");
            assertThat(payload.path("eventId").asText()).isEqualTo(row.get("id").toString());
            assertThat(payload.path("postingDate").asText()).isEqualTo("2025-02-03");
            assertThat(payload.path("lines").get(0).path("quantity").asText()).isEqualTo("1.1234567");
            assertThat(payload.path("lines").get(0).path("amount").asText()).isEqualTo("31.0042");
            UUID eventId = (UUID) row.get("id"), remoteId = UUID.randomUUID();
            var requests = new java.util.ArrayList<String>();
            var paths = new java.util.ArrayList<String>();
            var bodies = new java.util.ArrayList<String>();
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mm/v1/documents", exchange -> {
                requests.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                paths.add(exchange.getRequestURI().getPath());
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                byte[] response = (requests.size() == 1 ? "temporarily unavailable" :
                    "{\"protocolVersion\":1,\"eventId\":\"" + eventId + "\",\"status\":\"POSTED\",\"documentRef\":\"" + remoteId + "\",\"posted\":true}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(requests.size() == 1 ? 503 : 200, response.length);
                try (var out = exchange.getResponseBody()) { out.write(response); }
            });
            server.start();
            try {
                jdbc.update("update erp_exchange_connection set base_url=? where tenant_id=?", "http://127.0.0.1:" + server.getAddress().getPort(), tenant);
                var worker = new ErpDeliveryWorker(jdbc, mapper, tx);
                worker.deliver();
                assertThat(jdbc.queryForObject("select delivery_state from erp_exchange_envelope where id=?", String.class, eventId)).isEqualTo("RETRY");
                jdbc.update("update erp_exchange_envelope set next_attempt_at=current_timestamp where id=?", eventId);
                worker.deliver();
                assertThat(jdbc.queryForObject("select delivery_state from erp_exchange_envelope where id=?", String.class, eventId)).isEqualTo("POSTED");
                assertThat(jdbc.queryForObject("select remote_document_ref from erp_exchange_envelope where id=?", UUID.class, eventId)).isEqualTo(remoteId);
                assertThat(requests).containsExactly(eventId.toString(), eventId.toString());
                jdbc.update("update erp_exchange_envelope set delivery_state='AWAITING_CONFIRMATION',remote_posted=false where id=?", eventId);
                requests.clear(); paths.clear(); bodies.clear();
                var gate = new java.util.concurrent.CountDownLatch(1);
                var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
                try {
                    java.util.concurrent.Callable<String> confirm = () -> {
                        TenantContext.setTenant(tenant); CompanyContext.setCompany(company);
                        try { gate.await(); return tx.execute(status -> (String)service.confirm(eventId).get("status")); }
                        finally { TenantContext.clear(); CompanyContext.clear(); }
                    };
                    var one=pool.submit(confirm); var two=pool.submit(confirm); gate.countDown();
                    assertThat(one.get()).isEqualTo("QUEUED"); assertThat(two.get()).isEqualTo("QUEUED");
                } finally { pool.shutdownNow(); }
                assertThat(jdbc.queryForObject("select confirm_requested from erp_exchange_envelope where id=?", Boolean.class, eventId)).isTrue();
                worker.deliver();
                assertThat(jdbc.queryForObject("select delivery_state from erp_exchange_envelope where id=?", String.class, eventId)).isEqualTo("RETRY");
                assertThat(jdbc.queryForObject("select confirm_requested from erp_exchange_envelope where id=?", Boolean.class, eventId)).isTrue();
                jdbc.update("update erp_exchange_envelope set next_attempt_at=current_timestamp where id=?", eventId);
                worker.deliver();
                assertThat(jdbc.queryForObject("select delivery_state from erp_exchange_envelope where id=?", String.class, eventId)).isEqualTo("POSTED");
                assertThat(jdbc.queryForObject("select confirm_requested from erp_exchange_envelope where id=?", Boolean.class, eventId)).isFalse();
                assertThat(requests).containsExactly(eventId.toString(), eventId.toString());
                assertThat(paths).containsExactly("/mm/v1/documents/" + eventId + "/confirm", "/mm/v1/documents/" + eventId + "/confirm");
                assertThat(bodies).containsExactly((String)row.get("payload"), (String)row.get("payload"));
                String replayStatus = tx.execute(status -> (String) service.confirm(eventId).get("status"));
                assertThat(replayStatus).isEqualTo("POSTED");
            } finally { server.stop(0); }
        } finally {
            jdbc.update("delete from erp_exchange_envelope where tenant_id=?", tenant);
            jdbc.update("delete from erp_exchange_connection where tenant_id=?", tenant);
            TenantContext.clear(); CompanyContext.clear();
        }
    }
}

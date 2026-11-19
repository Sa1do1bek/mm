package com.enterprise.mm.integration.faktura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.service.IntegrationSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FakturaClientTest {
    private final UUID companyId = UUID.randomUUID();
    private final IntegrationSettingService settings = mock(IntegrationSettingService.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private IntegrationSettingService.RuntimeEndpoint endpoint;
    private HttpServer server;
    private FakturaClient client;
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        endpoint = new IntegrationSettingService.RuntimeEndpoint(
            "FAKTURA_UZ", "Faktura.uz", true, false, baseUrl, "OAUTH2_PASSWORD",
            "test-user", "test-password", null, null, null, "test-client", "test-secret",
            "/" + baseUrl + "/token", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);
        when(settings.endpoint(ExternalIntegrationSystem.FAKTURA_UZ)).thenReturn(endpoint);
        Company company = new Company();
        company.setTaxId("123456789");
        when(companies.findByTenantIdAndIdAndIsDeletedFalse("tenant-a", companyId)).thenReturn(Optional.of(company));
        client = new FakturaClient(settings, companies, new ObjectMapper());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void authenticatesVerifiesCompanyAndUsesFakturaDocumentRoutes() throws IOException {
        AtomicInteger authCalls = new AtomicInteger();
        server.createContext("/token", exchange -> {
            authCalls.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .contains("grant_type=password", "client_id=test-client", "client_secret=test-secret");
            respond(exchange, 200, "{\"access_token\":\"local-token\",\"expires_in\":3600}");
        });
        server.createContext("/Api/account/getuserdetails", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer local-token");
            respond(exchange, 200, "{\"companies\":[{\"inn\":\"123456789\"}]}");
        });
        server.createContext("/Api/Document/GetDocuments", exchange -> {
            assertThat(exchange.getRequestURI().getRawQuery())
                .contains("CompanyInn=123456789", "types=20", "limit=100", "skip=0", "CreatedFrom=", "CreatedTo=");
            respond(exchange, 200, "{\"documents\":[{\"uniqueId\":\"doc-1\"}]}");
        });
        server.createContext("/Api/Document/GetDocumentsContent", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getRawQuery()).contains("CompanyInn=123456789", "isDeserialized=true");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .contains("DocumentUniqueIds", "doc-1");
            respond(exchange, 200, "{\"Documents\":[{\"UniqueId\":\"doc-1\",\"Content\":{}}]}");
        });
        server.start();

        assertThat(client.documents(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), 20, 0, 100, false)
            .path("documents").size()).isEqualTo(1);
        assertThat(client.contents(List.of("doc-1")).path("Documents").size()).isEqualTo(1);
        assertThat(authCalls).hasValue(1);
    }

    @Test
    void rejectsAccountWithoutSelectedCompanyInn() throws IOException {
        server.createContext("/token", exchange -> respond(exchange, 200, "{\"access_token\":\"local-token\"}"));
        server.createContext("/Api/account/getuserdetails", exchange ->
            respond(exchange, 200, "{\"companies\":[{\"inn\":\"987654321\"}]}"));
        server.start();

        assertThatThrownBy(client::verifiedCompanyInn)
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not have access");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

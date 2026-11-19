package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.LaboratoryTestResponse;
import com.enterprise.mm.payload.QualityInspectionResponse;
import com.enterprise.mm.payload.IntegrationSettingUpdateRequest;
import com.enterprise.mm.payload.LaboratoryTestCreateRequest;
import com.enterprise.mm.payload.QualityInspectionCreateRequest;
import com.enterprise.mm.service.IntegrationSettingService;
import com.enterprise.mm.service.LaboratoryTestService;
import com.enterprise.mm.service.QualityInspectionService;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.enums.LaboratoryTestStatus;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "qc-manager", roles = "ADMIN")
class LaboratoryLimsIntegrationTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired QualityInspectionService inspectionService;
    @Autowired LaboratoryTestService laboratoryTestService;
    @Autowired IntegrationSettingService integrationSettingService;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired ObjectMapper objectMapper;

    @Test
    void sendToLimsCreatesSampleInLimsGeneralAndMarksOutboxPublished() throws Exception {
        try (FakeLimsServer lims = FakeLimsServer.start()) {
            configureLims(lims.baseUrl());
            QualityInspectionResponse inspection = inspection();
            inspection = inspectionService.startQuarantine(inspection.getId());
            assertThat(inspection.getStatus()).isEqualTo(QualityInspectionStatus.IN_QUARANTINE.name());
            inspection = inspectionService.startSampling(inspection.getId());
            assertThat(inspection.getStatus()).isEqualTo(QualityInspectionStatus.SAMPLING.name());

            LaboratoryTestCreateRequest labRequest = new LaboratoryTestCreateRequest();
            labRequest.setName("Chemical composition");
            labRequest.setQualityInspectionId(inspection.getId());
            labRequest.setSampleNumber("MM-SAMPLE-" + suffix());
            labRequest.setTestType("CHEMICAL_COMPOSITION");
            labRequest.setTestMethod("ICP-OES");
            labRequest.setSpecification("C <= 0.20%");
            LaboratoryTestResponse lab = laboratoryTestService.create(labRequest);
            UUID labId = lab.getId();

            LaboratoryTestResponse sent = laboratoryTestService.sendToLims(labId);

            assertThat(sent.getStatus()).isEqualTo(LaboratoryTestStatus.IN_PROGRESS.name());
            assertThat(sent.getLimsSampleId()).isEqualTo("TST-20260608-0001");
            assertThat(sent.getLimsStatus()).isEqualTo("CREATED");
            assertThat(lims.sampleRequests()).isEqualTo(1);
            assertThat(lims.lastUserEmail()).isEqualTo("admin@lims.uz");
            assertThat(lims.lastSourceSystem()).isEqualTo("lims-web");

            JsonNode request = objectMapper.readTree(lims.lastSampleRequest());
            assertThat(request.path("plantId").asText()).isEqualTo("plant-1");
            assertThat(request.path("labId").asText()).isEqualTo("lab-1");
            assertThat(request.path("sampleTypeId").asText()).isEqualTo("sample-type-1");
            assertThat(request.path("sampleSourceId").asText()).isEqualTo("sample-source-1");
            assertThat(request.path("priorityId").asText()).isEqualTo("priority-1");
            assertThat(request.path("externalRef").asText()).isEqualTo("MM-LAB-" + labId);
            assertThat(request.path("controlType").asText()).isEqualTo("INCOMING");

            IntegrationEvent event = integrationEventRepository.findAll().stream()
                .filter(item -> labId.equals(item.getAggregateId()))
                .filter(item -> "LIMS_LAB_TEST_REQUESTED".equals(item.getEventType()))
                .findFirst()
                .orElseThrow();
            assertThat(event.getAggregateType()).isEqualTo(ExternalIntegrationSystem.LIMS.name());
            assertThat(event.getStatus()).isEqualTo(IntegrationEventStatus.PUBLISHED.name());
            assertThat(event.getLastError()).contains("Delivered to LIMS sample TST-20260608-0001");
        }
    }

    private void configureLims(String baseUrl) {
        IntegrationSettingUpdateRequest dto = new IntegrationSettingUpdateRequest();
        dto.setProviderName("LIMS General");
        dto.setName("LIMS General");
        dto.setEnabled(true);
        dto.setMockMode(false);
        dto.setBaseUrl(baseUrl);
        dto.setAuthType("API_KEY");
        dto.setApiKeyHeader("X-User-Email");
        dto.setApiKey("admin@lims.uz");
        dto.setHealthPath("/api/v1/health");
        dto.setEventsPath("/api/v1/samples");
        dto.setDocumentPath("/api/v1/samples");
        integrationSettingService.saveSetting(ExternalIntegrationSystem.LIMS, dto);
    }

    private QualityInspectionResponse inspection() {
        Material material = material("MAT-LIMS");
        Warehouse warehouse = warehouse("WH-LIMS");
        StockBalance balance = new StockBalance();
        balance.setCode("BAL-LIMS-" + suffix());
        balance.setName("LIMS inspection stock");
        balance.setStatus("ACTIVE");
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setQuantity(BigDecimal.ONE);
        balance.setAvailableQuantity(BigDecimal.ONE);
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(BigDecimal.valueOf(25));
        stockBalanceRepository.save(balance);
        QualityInspectionCreateRequest inspection = new QualityInspectionCreateRequest();
        inspection.setName("LIMS QC");
        inspection.setInspectionNumber("QC-LIMS-" + suffix());
        inspection.setMaterialId(material.getId());
        inspection.setWarehouseId(warehouse.getId());
        inspection.setReceivedQuantity(BigDecimal.ONE);
        inspection.setSampleQuantity(BigDecimal.ONE);
        return inspectionService.create(inspection);
    }

    private Material material(String prefix) {
        String code = prefix + "-" + suffix();
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Warehouse warehouse(String prefix) {
        String code = prefix + "-" + suffix();
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final class FakeLimsServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger sampleRequests = new AtomicInteger();
        private final AtomicReference<String> lastSampleRequest = new AtomicReference<>("");
        private final AtomicReference<String> lastUserEmail = new AtomicReference<>("");
        private final AtomicReference<String> lastSourceSystem = new AtomicReference<>("");

        private FakeLimsServer(HttpServer server) {
            this.server = server;
        }

        static FakeLimsServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeLimsServer fake = new FakeLimsServer(server);
            server.createContext("/api/v1/plants", exchange -> fake.json(exchange, 200, """
                [{"id":"plant-1","code":"TST","name":"Test Plant","isActive":true}]
                """));
            server.createContext("/api/v1/labs", exchange -> fake.json(exchange, 200, """
                [{"id":"lab-1","code":"QUALITY","name":"Quality Lab","isActive":true}]
                """));
            server.createContext("/api/v1/dictionaries/sample-types", exchange -> fake.json(exchange, 200, """
                [{"id":"sample-type-1","code":"CHEMICAL_COMPOSITION","name":"Chemical Composition","isActive":true}]
                """));
            server.createContext("/api/v1/dictionaries/sample-sources", exchange -> fake.json(exchange, 200, """
                [{"id":"sample-source-1","code":"MM","name":"Material Management","isActive":true}]
                """));
            server.createContext("/api/v1/dictionaries/priorities", exchange -> fake.json(exchange, 200, """
                [{"id":"priority-1","code":"NORMAL","name":"Normal","isActive":true}]
                """));
            server.createContext("/api/v1/samples", fake::createSample);
            server.start();
            return fake;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int sampleRequests() {
            return sampleRequests.get();
        }

        String lastSampleRequest() {
            return lastSampleRequest.get();
        }

        String lastUserEmail() {
            return lastUserEmail.get();
        }

        String lastSourceSystem() {
            return lastSourceSystem.get();
        }

        private void createSample(HttpExchange exchange) throws IOException {
            sampleRequests.incrementAndGet();
            lastSampleRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastUserEmail.set(exchange.getRequestHeaders().getFirst("X-User-Email"));
            lastSourceSystem.set(exchange.getRequestHeaders().getFirst("X-Source-System"));
            json(exchange, 201, """
                {"id":"lims-sample-id-1","sampleNo":"TST-20260608-0001"}
                """);
        }

        private void json(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

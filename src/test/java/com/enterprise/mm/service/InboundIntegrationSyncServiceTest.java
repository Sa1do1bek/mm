package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.BudgetItem;
import com.enterprise.mm.entity.ExternalInboundRecord;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundSyncRequest;
import com.enterprise.mm.payload.ProductCodeSyncDtos.ProductCodeSyncRequest;
import com.enterprise.mm.payload.ProductCodeSyncDtos.ProductCodeSyncResult;
import com.enterprise.mm.repository.BudgetItemRepository;
import com.enterprise.mm.repository.ChartOfAccountRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ExternalInboundRecordRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InboundIntegrationSyncServiceTest {
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000008888");

    @Mock
    private IntegrationSettingService settingService;
    @Mock
    private IntegrationEventService eventService;
    @Mock
    private HrIntegrationService hrIntegrationService;
    @Mock
    private ProductCodeSyncService productCodeSyncService;
    @Mock
    private ExternalInboundRecordRepository inboundRecordRepository;
    @Mock
    private BudgetItemRepository budgetItemRepository;
    @Mock
    private ChartOfAccountRepository chartOfAccountRepository;
    @Mock
    private ContractRepository contractRepository;
    @Mock
    private ContractInvoiceRepository contractInvoiceRepository;
    @Mock
    private ContractPaymentRepository contractPaymentRepository;
    @Mock
    private MaterialRepository materialRepository;
    @Mock
    private WarehouseRepository warehouseRepository;
    @Mock
    private StorageLocationRepository storageLocationRepository;
    @Mock
    private StockBalanceRepository stockBalanceRepository;

    private InboundIntegrationSyncService service;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-inbound-test");
        CompanyContext.setCompany(COMPANY_ID);
        service = new InboundIntegrationSyncService(
            settingService,
            eventService,
            hrIntegrationService,
            productCodeSyncService,
            inboundRecordRepository,
            budgetItemRepository,
            chartOfAccountRepository,
            contractRepository,
            contractInvoiceRepository,
            contractPaymentRepository,
            materialRepository,
            warehouseRepository,
            storageLocationRepository,
            stockBalanceRepository,
            new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void syncSystemFetchesLiveBudgetItemsAndStagesInboundRecord() throws Exception {
        String baseUrl = startServer("/budget-items", """
            {
              "data": [
                {
                  "externalId": "BUD-EXT-1",
                  "budgetCode": "BDG-01",
                  "name": "Maintenance budget",
                  "status": "ACTIVE",
                  "annualLimit": "1000.50",
                  "usedAmount": "250,25",
                  "description": "Plant maintenance"
                }
              ]
            }
            """);
        when(settingService.endpoint(ExternalIntegrationSystem.BUDGETING))
            .thenReturn(endpoint(ExternalIntegrationSystem.BUDGETING, baseUrl, true, false));
        when(budgetItemRepository.findByTenantIdAndCodeAndIsDeletedFalse("tenant-inbound-test", "BDG-01"))
            .thenReturn(Optional.empty());
        when(budgetItemRepository.save(any(BudgetItem.class))).thenAnswer(invocation -> {
            BudgetItem item = invocation.getArgument(0);
            item.setId(UUID.randomUUID());
            return item;
        });
        when(inboundRecordRepository.findByTenantIdAndCompanyIdAndSourceSystemAndDatasetAndExternalIdAndIsDeletedFalse(
            "tenant-inbound-test", COMPANY_ID, "BUDGETING", "BUDGET_ITEMS", "BUD-EXT-1"
        )).thenReturn(Optional.empty());
        when(inboundRecordRepository.save(any(ExternalInboundRecord.class))).thenAnswer(invocation -> {
            ExternalInboundRecord record = invocation.getArgument(0);
            record.setId(UUID.randomUUID());
            return record;
        });
        stubEmittedEvent();

        var result = service.syncSystem(ExternalIntegrationSystem.BUDGETING, new InboundSyncRequest(
            List.of("BUDGETING"),
            List.of("BUDGET_ITEMS"),
            Map.of("BUDGETING.BUDGET_ITEMS", "/budget-items"),
            false,
            true,
            10
        ));

        assertThat(result.system()).isEqualTo(ExternalIntegrationSystem.BUDGETING);
        assertThat(result.received()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.archived()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.datasets().get(0).mode()).isEqualTo("LIVE");
        ArgumentCaptor<BudgetItem> budgetItem = ArgumentCaptor.forClass(BudgetItem.class);
        verify(budgetItemRepository).save(budgetItem.capture());
        assertThat(budgetItem.getValue().getCode()).isEqualTo("BDG-01");
        assertThat(budgetItem.getValue().getAnnualLimit()).isEqualByComparingTo("1000.50");
        assertThat(budgetItem.getValue().getUsedAmount()).isEqualByComparingTo("250.25");
        ArgumentCaptor<ExternalInboundRecord> inboundRecord = ArgumentCaptor.forClass(ExternalInboundRecord.class);
        verify(inboundRecordRepository).save(inboundRecord.capture());
        assertThat(inboundRecord.getValue().getAppliedEntityType()).isEqualTo("BudgetItem");
        assertThat(inboundRecord.getValue().getPayloadHash()).hasSize(64);
        verify(settingService).archiveOriginalDocument(ExternalIntegrationSystem.BUDGETING, "BUD-EXT-1");
        verify(eventService).emit(eq("INBOUND.BUDGETING.BUDGET_ITEMS.SYNCED"), eq("BUDGETING"), isNull(), anyMap());
    }

    @Test
    void syncSystemReturnsErrorDatasetWhenRequireLiveEndpointIsUnavailable() {
        when(settingService.endpoint(ExternalIntegrationSystem.BUDGETING))
            .thenReturn(endpoint(ExternalIntegrationSystem.BUDGETING, null, false, true));
        stubEmittedEvent();

        var result = service.syncSystem(ExternalIntegrationSystem.BUDGETING, new InboundSyncRequest(
            List.of("BUDGETING"),
            List.of("BUDGET_ITEMS"),
            Map.of(),
            true,
            false,
            10
        ));

        assertThat(result.received()).isZero();
        assertThat(result.created()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.archived()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.datasets()).hasSize(1);
        assertThat(result.datasets().get(0).mode()).isEqualTo("ERROR");
        assertThat(result.datasets().get(0).message()).contains("not configured for live inbound sync");
    }

    @Test
    void syncSystemDelegatesSoliqMxikDatasetToProductCodeSyncService() {
        when(productCodeSyncService.syncMxik(any(ProductCodeSyncRequest.class))).thenReturn(new ProductCodeSyncResult(
            "MXIK",
            "SOLIQ",
            "MOCK",
            12,
            4,
            3,
            1,
            true,
            "Product code sync completed",
            LocalDateTime.now()
        ));
        stubEmittedEvent();

        var result = service.syncSystem(ExternalIntegrationSystem.SOLIQ, new InboundSyncRequest(
            List.of("SOLIQ"),
            List.of("MXIK_CODES"),
            Map.of("SOLIQ.MXIK_CODES", "https://example.test/mxik"),
            true,
            false,
            10
        ));

        assertThat(result.received()).isEqualTo(12);
        assertThat(result.created()).isEqualTo(4);
        assertThat(result.updated()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
        assertThat(result.datasets().get(0).sourcePath()).isEqualTo("https://example.test/mxik");
        assertThat(result.datasets().get(0).message()).isEqualTo("Product code sync completed");
        ArgumentCaptor<ProductCodeSyncRequest> request = ArgumentCaptor.forClass(ProductCodeSyncRequest.class);
        verify(productCodeSyncService).syncMxik(request.capture());
        assertThat(request.getValue().requireLive()).isTrue();
        assertThat(request.getValue().sourcePath()).isEqualTo("https://example.test/mxik");
    }

    private void stubEmittedEvent() {
        when(eventService.emit(anyString(), anyString(), isNull(), anyMap())).thenAnswer(invocation -> {
            IntegrationEvent event = new IntegrationEvent();
            event.setId(UUID.randomUUID());
            event.setEventType(invocation.getArgument(0));
            event.setAggregateType(invocation.getArgument(1));
            return event;
        });
    }

    private String startServer(String path, String json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private IntegrationSettingService.RuntimeEndpoint endpoint(
        ExternalIntegrationSystem system,
        String baseUrl,
        boolean enabled,
        boolean mockMode
    ) {
        return new IntegrationSettingService.RuntimeEndpoint(
            system.name(),
            system.name(),
            enabled,
            mockMode,
            baseUrl,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "/health",
            "/events",
            "/invoices",
            "/documents",
            "/supplier-status",
            null,
            null,
            null,
            null,
            null,
            null,
            BigDecimal.valueOf(0.2),
            1024,
            30
        );
    }
}

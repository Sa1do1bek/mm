package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.IntegrationProperties;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrationDispatchServiceTest {
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000007777");
    private static final UUID OTHER_COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000007778");

    @Mock
    private IntegrationEventRepository eventRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private IntegrationSettingService settingService;
    @Mock
    private IntegrationMessageQueuePublisher queuePublisher;

    private IntegrationProperties properties;
    private IntegrationDispatchService service;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-dispatch-test");
        CompanyContext.setCompany(COMPANY_ID);
        properties = new IntegrationProperties();
        service = new IntegrationDispatchService(
            new com.enterprise.mm.security.pilot.PilotCapabilityPolicy("SERIALIZED_EXECUTION,MRP_GENERATION,MM_FINANCE_EXECUTION,FIXED_ASSET_EXECUTION,SERVICE_EXECUTION"),
            eventRepository,
            auditService,
            properties,
            settingService,
            queuePublisher,
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
    void excludedHistoricalFinanceReplayAndScheduledDispatchNeverReachTransport() {
        service = new IntegrationDispatchService(new com.enterprise.mm.security.pilot.PilotCapabilityPolicy(""),
            eventRepository, auditService, properties, settingService, queuePublisher, new ObjectMapper());
        IntegrationEvent event = event(IntegrationEventStatus.NEW, "TREASURY", COMPANY_ID);
        when(eventRepository.findDue(any(), any())).thenReturn(List.of(event));
        assertThat(service.dispatchDue()).isZero();
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test", event.getId())).thenReturn(Optional.of(event));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.dispatch(event.getId()))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class).hasMessageContaining("MM_FINANCE_EXECUTION");
        assertThat(event.getLastAttemptAt()).isNull();
        assertThat(event.getStatus()).isEqualTo("NEW");
        org.mockito.Mockito.verifyNoInteractions(settingService, queuePublisher, auditService);
        verify(eventRepository, never()).save(any());
    }
    @Test
    void nativeTreasuryModeBlocksScheduledManualAndMislabelledLegacyEventsBeforeAllTransports() {
        var nativeAccess=new com.enterprise.mm.treasury.TreasuryNativeAccess(true,"12345678901234567890123456789012","tenant-dispatch-test",COMPANY_ID.toString(),"test-base",true);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"treasuryNativeAccess",nativeAccess);
        IntegrationEvent event=event(IntegrationEventStatus.NEW,"TREASURY",COMPANY_ID);
        when(eventRepository.findDue(any(),any())).thenReturn(List.of(event));assertThat(service.dispatchDue()).isZero();
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test",event.getId())).thenReturn(Optional.of(event));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.dispatch(event.getId())).hasMessageContaining("native-initiated");
        event.setAggregateType("OTHER");event.setEventType("TREASURY.PAYMENT_REQUEST");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.dispatch(event.getId())).hasMessageContaining("native-initiated");
        org.mockito.Mockito.verifyNoInteractions(settingService,queuePublisher,auditService);verify(eventRepository,never()).save(any());
    }

    @Test
    void dispatchReturnsPublishedEventWithoutMutatingIt() {
        IntegrationEvent event = event(IntegrationEventStatus.PUBLISHED, "MATERIAL", COMPANY_ID);
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test", event.getId()))
            .thenReturn(Optional.of(event));

        IntegrationEvent result = service.dispatch(event.getId());

        assertThat(result).isSameAs(event);
        verify(eventRepository, never()).save(any());
    }
    @Test void deliveredNativeSourceEventCannotBeDispatchedAfterTransportSwitch() {
        IntegrationEvent event=event(IntegrationEventStatus.NEW,"TREASURY",COMPANY_ID);event.setStatus("DELIVERED");
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test",event.getId())).thenReturn(Optional.of(event));
        assertThat(service.dispatch(event.getId())).isSameAs(event);
        assertThat(event.getStatus()).isEqualTo("DELIVERED");org.mockito.Mockito.verifyNoInteractions(settingService,queuePublisher,auditService);verify(eventRepository,never()).save(any());
    }

    @Test
    void dispatchRetriesWhenNoExternalTransportConfigured() {
        IntegrationEvent event = event(IntegrationEventStatus.NEW, "MATERIAL", COMPANY_ID);
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test", event.getId()))
            .thenReturn(Optional.of(event));
        when(queuePublisher.enabled()).thenReturn(false);
        when(eventRepository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationEvent result = service.dispatch(event.getId());

        assertThat(result.getStatus()).isEqualTo(IntegrationEventStatus.RETRY.name());
        assertThat(result.getNextRetryAt()).isAfter(LocalDateTime.now());
        assertThat(result.getLastError()).isEqualTo("No integration transport is configured");
        verify(queuePublisher, never()).publish(any(), any());
        verify(auditService).log("IntegrationEvent", event.getId(), "DISPATCH_FAILED", result.getLastError(), null, null);
    }

    @Test
    void dispatchPublishesToQueueWhenQueueIsEnabled() {
        IntegrationEvent event = event(IntegrationEventStatus.NEW, "MATERIAL", COMPANY_ID);
        properties.getMessageQueue().setExchange("mm.test.exchange");
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test", event.getId()))
            .thenReturn(Optional.of(event));
        when(queuePublisher.enabled()).thenReturn(true);
        when(eventRepository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationEvent result = service.dispatch(event.getId());

        assertThat(result.getStatus()).isEqualTo(IntegrationEventStatus.PUBLISHED.name());
        assertThat(result.getLastError()).isEqualTo("Published to RabbitMQ exchange mm.test.exchange");
        verify(queuePublisher).publish(event, """
            {"eventId":"%s","eventType":"%s","aggregateType":"MATERIAL","aggregateId":"%s","payload":{"payload":{"quantity":5}}}""".formatted(event.getId(), event.getEventType(), event.getAggregateId()));
    }

    @Test
    void dispatchPostsSystemEndpointPayloadWithIntegrationHeaders() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        String baseUrl = startServer("/events", 200, "", requestBody, requestHeaders);
        IntegrationEvent event = event(IntegrationEventStatus.NEW, ExternalIntegrationSystem.PRODUCTION.name(), COMPANY_ID);
        event.setPayload("""
            {"targetPath":"/events","raw":{"orderNumber":"PO-1","quantity":3}}""");
        when(settingService.endpoint(ExternalIntegrationSystem.PRODUCTION))
            .thenReturn(endpoint(ExternalIntegrationSystem.PRODUCTION, baseUrl, true, false));
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test", event.getId()))
            .thenReturn(Optional.of(event));
        when(eventRepository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationEvent result = service.dispatch(event.getId());

        assertThat(result.getStatus()).isEqualTo(IntegrationEventStatus.PUBLISHED.name());
        assertThat(result.getLastError()).isEqualTo("Delivered to PRODUCTION endpoint");
        assertThat(requestBody.get()).isEqualTo("{\"orderNumber\":\"PO-1\",\"quantity\":3}");
        assertThat(requestHeaders.get().getFirst("X-MM-Integration-System")).isEqualTo("PRODUCTION");
        assertThat(requestHeaders.get().getFirst("X-MM-Event-Id")).isEqualTo(event.getId().toString());
        assertThat(requestHeaders.get().getFirst("X-MM-Tenant-Id")).isEqualTo("tenant-dispatch-test");
        assertThat(requestHeaders.get().getFirst("X-MM-Company-Id")).isEqualTo(COMPANY_ID.toString());
        assertThat(requestHeaders.get().getFirst("X-Role")).isEqualTo("INTEGRATION_OPERATOR");
    }

    @Test
    void dispatchSignsTheRawEnvelopeIdForWmsConsumer() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        String baseUrl = startServer("/api/integration/envelopes", 202, "", requestBody, requestHeaders);
        UUID envelopeId = UUID.randomUUID();
        IntegrationEvent event = event(IntegrationEventStatus.NEW, ExternalIntegrationSystem.WMS.name(), COMPANY_ID);
        event.setPayload("""
            {"targetPath":"/api/integration/envelopes","raw":{"id":"%s","source":"mm","target":"wms"}}""".formatted(envelopeId));
        when(settingService.endpoint(ExternalIntegrationSystem.WMS))
            .thenReturn(endpoint(ExternalIntegrationSystem.WMS, baseUrl, true, false, "wms-test-secret"));
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test", event.getId()))
            .thenReturn(Optional.of(event));
        when(eventRepository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationEvent result = service.dispatch(event.getId());

        assertThat(result.getStatus()).isEqualTo(IntegrationEventStatus.PUBLISHED.name());
        String timestamp = requestHeaders.get().getFirst("X-MM-Timestamp");
        assertThat(requestHeaders.get().getFirst("X-MM-Signature"))
            .isEqualTo(hmac("wms-test-secret", envelopeId + "|" + timestamp + "|" + requestBody.get()));
    }

    @Test
    void dispatchDeliversMaterialEnvelopeToErpWithMachineBearerCredential() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        String baseUrl = startServer("/api/integration/envelopes", 202, "", requestBody, requestHeaders);
        UUID envelopeId = UUID.randomUUID();
        IntegrationEvent event = event(IntegrationEventStatus.NEW, ExternalIntegrationSystem.ERP.name(), COMPANY_ID);
        event.setPayload("""
            {"targetPath":"/api/integration/envelopes","raw":{"id":"%s","source":"mm","target":"erp","type":"mm.material.changed.v1"}}""".formatted(envelopeId));
        when(settingService.endpoint(ExternalIntegrationSystem.ERP))
            .thenReturn(erpEndpoint(baseUrl, "erp-machine-token"));
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test", event.getId()))
            .thenReturn(Optional.of(event));
        when(eventRepository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationEvent result = service.dispatch(event.getId());

        assertThat(result.getStatus()).isEqualTo(IntegrationEventStatus.PUBLISHED.name());
        assertThat(requestBody.get()).contains("\"id\":\"" + envelopeId + "\"");
        assertThat(requestHeaders.get().getFirst("Authorization")).isEqualTo("Bearer erp-machine-token");
        assertThat(requestHeaders.get().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(requestHeaders.get().getFirst("Idempotency-Key")).isEqualTo(event.getIdempotencyKey());
    }

    @Test
    void dispatchMovesEventToRetryWhenWebhookReturnsError() throws Exception {
        String baseUrl = startServer("/webhook", 503, "provider unavailable", new AtomicReference<>(), new AtomicReference<>());
        properties.setWebhookUrl(baseUrl + "/webhook");
        IntegrationEvent event = event(IntegrationEventStatus.NEW, "MATERIAL", COMPANY_ID);
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test", event.getId()))
            .thenReturn(Optional.of(event));
        when(eventRepository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationEvent result = service.dispatch(event.getId());

        assertThat(result.getStatus()).isEqualTo(IntegrationEventStatus.RETRY.name());
        assertThat(result.getRetryCount()).isEqualTo(1);
        assertThat(result.getNextRetryAt()).isAfter(LocalDateTime.now());
        assertThat(result.getLastError()).contains("HTTP 503", "provider unavailable");
        verify(auditService).log("IntegrationEvent", event.getId(), "DISPATCH_FAILED", result.getLastError(), null, null);
    }

    @Test
    void dispatchStopsAutomaticRetriesAtDeadLetterThreshold() {
        IntegrationEvent event = event(IntegrationEventStatus.RETRY, "MATERIAL", COMPANY_ID);
        event.setRetryCount(IntegrationDispatchService.MAX_AUTOMATIC_RETRIES - 1);
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-dispatch-test", event.getId()))
            .thenReturn(Optional.of(event));
        when(queuePublisher.enabled()).thenReturn(false);
        when(eventRepository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationEvent result = service.dispatch(event.getId());

        assertThat(result.getStatus()).isEqualTo(IntegrationEventStatus.DEAD.name());
        assertThat(result.getRetryCount()).isEqualTo(IntegrationDispatchService.MAX_AUTOMATIC_RETRIES);
        assertThat(result.getNextRetryAt()).isNull();
        assertThat(result.getLastAttemptAt()).isNotNull();
    }

    @Test
    void pendingFiltersEventsByCurrentCompanyWhenCallerIsNotGlobalAdmin() {
        IntegrationEvent accessible = event(IntegrationEventStatus.NEW, "MATERIAL", COMPANY_ID);
        IntegrationEvent foreign = event(IntegrationEventStatus.RETRY, "MATERIAL", OTHER_COMPANY_ID);
        when(eventRepository.findByTenantIdAndStatusInAndIsDeletedFalse(
            "tenant-dispatch-test", List.of(IntegrationEventStatus.NEW.name(), IntegrationEventStatus.RETRY.name())
        )).thenReturn(List.of(accessible, foreign));

        List<IntegrationEvent> pending = service.pending();

        assertThat(pending).containsExactly(accessible);
    }

    @Test
    void dispatchDueUsesEventTenantAndCompanyThenRestoresPreviousContext() {
        UUID dueCompanyId = UUID.fromString("00000000-0000-0000-0000-000000007779");
        IntegrationEvent due = event(IntegrationEventStatus.NEW, "MATERIAL", dueCompanyId);
        due.setTenantId("tenant-due-event");
        when(eventRepository.findDue(any(), any())).thenReturn(List.of(due));
        when(eventRepository.findByTenantIdAndIdAndIsDeletedFalse("tenant-due-event", due.getId()))
            .thenReturn(Optional.of(due));
        when(queuePublisher.enabled()).thenReturn(false);
        when(eventRepository.save(any(IntegrationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int dispatched = service.dispatchDue();

        assertThat(dispatched).isEqualTo(1);
        assertThat(due.getStatus()).isEqualTo(IntegrationEventStatus.RETRY.name());
        assertThat(TenantContext.currentTenant()).isEqualTo("tenant-dispatch-test");
        assertThat(CompanyContext.currentCompany()).isEqualTo(COMPANY_ID);
    }

    private IntegrationEvent event(IntegrationEventStatus status, String aggregateType, UUID companyId) {
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId("tenant-dispatch-test");
        event.setCompanyId(companyId);
        event.setCode("EVT-" + event.getId());
        event.setName("Integration event");
        event.setStatus(status.name());
        event.setEventType("MM.EVENT.CREATED");
        event.setAggregateType(aggregateType);
        event.setAggregateId(UUID.randomUUID());
        event.setPayload("{\"payload\":{\"quantity\":5}}");
        event.setRetryCount(0);
        event.setIdempotencyKey("idem-" + event.getId());
        return event;
    }

    private String startServer(
        String path,
        int status,
        String response,
        AtomicReference<String> requestBody,
        AtomicReference<Headers> requestHeaders
    ) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestHeaders.set(exchange.getRequestHeaders());
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
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
        return endpoint(system, baseUrl, enabled, mockMode, null);
    }

    private IntegrationSettingService.RuntimeEndpoint endpoint(
        ExternalIntegrationSystem system,
        String baseUrl,
        boolean enabled,
        boolean mockMode,
        String clientSecret
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
            clientSecret,
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

    private IntegrationSettingService.RuntimeEndpoint erpEndpoint(String baseUrl, String bearerToken) {
        return new IntegrationSettingService.RuntimeEndpoint(
            ExternalIntegrationSystem.ERP.name(),
            "ERP Integration Core",
            true,
            false,
            baseUrl,
            "BEARER",
            null,
            null,
            bearerToken,
            null,
            null,
            null,
            null,
            null,
            "/health",
            "/api/integration/envelopes",
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

    private String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}

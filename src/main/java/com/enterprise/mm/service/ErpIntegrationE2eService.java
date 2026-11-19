package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.EnterpriseIntegrationDtos.EnterpriseIntegrationRequest;
import com.enterprise.mm.payload.ErpIntegrationE2eDtos.ErpIntegrationE2eReport;
import com.enterprise.mm.payload.ErpIntegrationE2eDtos.ErpIntegrationE2eRequest;
import com.enterprise.mm.payload.ErpIntegrationE2eDtos.ErpIntegrationE2eSuiteResult;
import com.enterprise.mm.payload.ErpIntegrationE2eDtos.ErpIntegrationE2eSystemResult;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationHealthDto;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundDatasetSyncResult;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundSyncRequest;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundSystemSyncResult;
import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ErpIntegrationE2eService {
    private static final List<Scenario> SCENARIOS = List.of(
        new Scenario("UZASSETS", "UzAssets / бухгалтерия, 1C, бюджетирование, казначейство", ExternalIntegrationSystem.FI, "STOCK_RECEIPT_POSTED"),
        new Scenario("UZASSETS", "UzAssets / бухгалтерия, 1C, бюджетирование, казначейство", ExternalIntegrationSystem.TREASURY, "PAYMENT_REQUEST"),
        new Scenario("UZASSETS", "UzAssets / бухгалтерия, 1C, бюджетирование, казначейство", ExternalIntegrationSystem.BUDGETING, "BUDGET_CHECK_REQUESTED"),
        new Scenario("UZASSETS", "UzAssets / бухгалтерия, 1C, бюджетирование, казначейство", ExternalIntegrationSystem.ONE_C, "ACCOUNTING_ENTRY"),
        new Scenario("MES", "MES / производство, WMS, SCADA", ExternalIntegrationSystem.PRODUCTION, "MATERIAL_DEMAND"),
        new Scenario("MES", "MES / производство, WMS, SCADA", ExternalIntegrationSystem.WMS, "STOCK_BALANCE_SYNC"),
        new Scenario("MES", "MES / производство, WMS, SCADA", ExternalIntegrationSystem.SCADA, "CONSUMPTION_SIGNAL"),
        new Scenario("HH", "HH / кадры и оргструктура", ExternalIntegrationSystem.HR, "SYNC_REQUESTED"),
        new Scenario("DOCFLOW", "DocFlow / ЭДО и документы", ExternalIntegrationSystem.EDO, "CONTRACT_ACTIVE"),
        new Scenario("DOCFLOW", "DocFlow / ЭДО и документы", ExternalIntegrationSystem.ETTN, "TRANSPORT_DOCUMENT")
    );
    private final IntegrationSettingService integrationSettingService;
    private final EnterpriseIntegrationApiService enterpriseIntegrationApiService;
    private final InboundIntegrationSyncService inboundIntegrationSyncService;

    @Transactional
    public ErpIntegrationE2eReport run(ErpIntegrationE2eRequest request) {
        boolean requireLive = Boolean.TRUE.equals(request == null ? null : request.requireLive());
        boolean dispatchOutbound = Boolean.TRUE.equals(request == null ? null : request.dispatchOutbound());
        boolean syncInbound = !Boolean.FALSE.equals(request == null ? null : request.syncInbound());
        boolean archiveOriginals = Boolean.TRUE.equals(request == null ? null : request.archiveOriginals());
        int limit = request == null || request.limit() == null ? 50 : Math.max(1, request.limit());
        List<Scenario> selected = selectedScenarios(request);
        Map<String, List<Scenario>> bySuite = new LinkedHashMap<>();
        for (Scenario scenario : selected) {
            bySuite.computeIfAbsent(scenario.suite(), ignored -> new ArrayList<>()).add(scenario);
        }
        List<ErpIntegrationE2eSuiteResult> suites = new ArrayList<>();
        for (Map.Entry<String, List<Scenario>> entry : bySuite.entrySet()) {
            List<ErpIntegrationE2eSystemResult> systems = entry.getValue().stream()
                .map(scenario -> runScenario(scenario, requireLive, dispatchOutbound, syncInbound, archiveOriginals, limit))
                .toList();
            suites.add(suite(entry.getKey(), entry.getValue().get(0).suiteName(), systems));
        }
        int blockers = suites.stream().mapToInt(ErpIntegrationE2eSuiteResult::blockers).sum();
        String status = aggregate(suites.stream().map(ErpIntegrationE2eSuiteResult::status).toList());
        return new ErpIntegrationE2eReport(
            LocalDateTime.now(),
            status,
            blockers == 0 && !"FAIL".equals(status),
            suites.size(),
            suites.stream().mapToInt(ErpIntegrationE2eSuiteResult::systemsProcessed).sum(),
            suites.stream().mapToInt(ErpIntegrationE2eSuiteResult::outboundPublished).sum(),
            suites.stream().mapToInt(ErpIntegrationE2eSuiteResult::inboundReceived).sum(),
            blockers,
            suites
        );
    }
    private ErpIntegrationE2eSystemResult runScenario(
        Scenario scenario,
        boolean requireLive,
        boolean dispatchOutbound,
        boolean syncInbound,
        boolean archiveOriginals,
        int limit
    ) {
        IntegrationSettingService.RuntimeEndpoint endpoint = integrationSettingService.endpoint(scenario.system());
        IntegrationHealthDto health = health(scenario.system());
        String mode = mode(endpoint);
        List<String> actions = actions(scenario, endpoint, health, requireLive);
        IntegrationEventResponse outbound = null;
        InboundSystemSyncResult inbound = null;
        String status = "PASS";
        if (requireLive && !"LIVE_OK".equals(health.status())) {
            status = "FAIL";
        } else if (!"LIVE_OK".equals(health.status()) || !"LIVE".equals(mode)) {
            status = "WARN";
        }
        if (dispatchOutbound) {
            outbound = dispatch(scenario);
            if (!"PUBLISHED".equals(outbound.getStatus())) {
                status = "FAIL";
                actions.add("Проверьте target path, auth и доступность live endpoint для outbound dispatch");
            }
        }
        if (syncInbound) {
            inbound = inbound(scenario.system(), requireLive, archiveOriginals, limit);
            if (hasInboundError(inbound)) {
                status = requireLive ? "FAIL" : worse(status, "WARN");
                actions.add("Проверьте inbound paths и формат ответа для datasets " + scenario.system().name());
            } else if (inbound.received() == 0) {
                status = worse(status, requireLive ? "FAIL" : "WARN");
                actions.add("Источник не вернул данные: заполните тестовые записи в соседнем модуле и повторите sync");
            }
        }
        String inboundMode = inbound == null ? null : inbound.datasets().stream()
            .map(InboundDatasetSyncResult::mode)
            .distinct()
            .reduce((left, right) -> left + "," + right)
            .orElse(null);
        String inboundMessage = inbound == null ? null : inbound.datasets().stream()
            .map(InboundDatasetSyncResult::message)
            .filter(message -> message != null && !message.isBlank())
            .findFirst()
            .orElse(null);
        return new ErpIntegrationE2eSystemResult(
            scenario.suite(),
            scenario.system().name(),
            scenario.system().displayName(),
            status,
            mode,
            endpoint.baseUrl(),
            health.status(),
            health.message(),
            scenario.system().name() + "." + scenario.eventType(),
            outbound == null ? null : outbound.getId(),
            outbound == null ? null : outbound.getStatus(),
            outbound == null ? null : outbound.getLastError(),
            inbound == null ? 0 : inbound.received(),
            inbound == null ? 0 : inbound.created(),
            inbound == null ? 0 : inbound.updated(),
            inbound == null ? 0 : inbound.skipped(),
            inboundMode,
            inboundMessage,
            actions
        );
    }
    private ErpIntegrationE2eSuiteResult suite(String code, String name, List<ErpIntegrationE2eSystemResult> systems) {
        String status = aggregate(systems.stream().map(ErpIntegrationE2eSystemResult::status).toList());
        return new ErpIntegrationE2eSuiteResult(
            code,
            name,
            status,
            systems.size(),
            (int) systems.stream().filter(item -> "PUBLISHED".equals(item.outboundStatus())).count(),
            systems.stream().mapToInt(ErpIntegrationE2eSystemResult::inboundReceived).sum(),
            (int) systems.stream().filter(item -> "FAIL".equals(item.status())).count(),
            systems
        );
    }
    private List<Scenario> selectedScenarios(ErpIntegrationE2eRequest request) {
        Set<String> suites = normalizeSet(request == null ? null : request.suites());
        Set<ExternalIntegrationSystem> systems = systems(request == null ? null : request.systems());
        return SCENARIOS.stream()
            .filter(scenario -> suites.isEmpty() || suites.contains(scenario.suite()))
            .filter(scenario -> systems.isEmpty() || systems.contains(scenario.system()))
            .toList();
    }
    private Set<String> normalizeSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
            }
        }
        return out;
    }
    private Set<ExternalIntegrationSystem> systems(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<ExternalIntegrationSystem> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(ExternalIntegrationSystem.from(value));
            }
        }
        return out;
    }
    private IntegrationHealthDto health(ExternalIntegrationSystem system) {
        try {
            return integrationSettingService.health(system);
        } catch (Exception ex) {
            return new IntegrationHealthDto(system.name(), false, false, false, "LIVE_ERROR", ex.getMessage(), LocalDateTime.now());
        }
    }
    private IntegrationEventResponse dispatch(Scenario scenario) {
        EnterpriseIntegrationRequest request = new EnterpriseIntegrationRequest(
            UUID.randomUUID(),
            "MM-E2E-" + scenario.system().name() + "-" + UUID.randomUUID(),
            "MM-E2E-" + scenario.system().name(),
            LocalDate.now(),
            scenario.eventType(),
            "POSTED",
            BigDecimal.valueOf(1_250_000),
            "UZS",
            "307123456",
            "TOSHKENT SANOAT TA'MINOTI MChJ",
            "CNT-E2E-001",
            "PO-E2E-001",
            "MM-OPEX",
            "SUPPLY",
            "WH-TST",
            "A-01",
            "MAT-E2E-001",
            BigDecimal.TEN,
            "PCS",
            null,
            samplePayload(scenario),
            null,
            true
        );
        return enterpriseIntegrationApiService.queue(scenario.system(), scenario.eventType(), request, "erp-e2e-" + UUID.randomUUID());
    }
    private Map<String, Object> samplePayload(Scenario scenario) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scenario", scenario.suite() + "/" + scenario.system().name());
        payload.put("sourceSystem", "MM");
        payload.put("externalReference", "MM-E2E");
        payload.put("companyTin", "307123456");
        payload.put("documentNumber", "MM-E2E-001");
        payload.put("documentDate", LocalDate.now().toString());
        payload.put("amount", "1250000");
        payload.put("currency", "UZS");
        payload.put("materialCode", "MAT-E2E-001");
        payload.put("warehouseCode", "WH-TST");
        payload.put("quantity", "10");
        return payload;
    }
    private InboundSystemSyncResult inbound(ExternalIntegrationSystem system, boolean requireLive, boolean archiveOriginals, int limit) {
        return inboundIntegrationSyncService.syncSystem(system, new InboundSyncRequest(
            List.of(system.name()),
            null,
            null,
            requireLive,
            archiveOriginals,
            limit
        ));
    }
    private boolean hasInboundError(InboundSystemSyncResult inbound) {
        return inbound != null && inbound.datasets().stream().anyMatch(dataset -> "ERROR".equals(dataset.mode()));
    }
    private String mode(IntegrationSettingService.RuntimeEndpoint endpoint) {
        if (!endpoint.enabled()) {
            return "DISABLED";
        }
        if (!endpoint.configured()) {
            return "CONFIG_ONLY";
        }
        return endpoint.mockMode() ? "MOCK" : "LIVE";
    }
    private List<String> actions(Scenario scenario, IntegrationSettingService.RuntimeEndpoint endpoint, IntegrationHealthDto health, boolean requireLive) {
        List<String> actions = new ArrayList<>();
        if (!endpoint.enabled()) {
            actions.add("Включите интеграцию " + scenario.system().name() + " в настройках");
        }
        if (!endpoint.configured()) {
            actions.add("Укажите base URL для " + scenario.system().displayName());
        }
        if (endpoint.mockMode()) {
            actions.add("Отключите mock mode для live E2E");
        }
        if (!"LIVE_OK".equals(health.status())) {
            actions.add(credentialsAction(scenario.system()));
        }
        if (requireLive && (endpoint.baseUrl() == null || endpoint.baseUrl().isBlank())) {
            actions.add("Запустите соседний сервис и проверьте health endpoint до запуска live E2E");
        }
        return new ArrayList<>(actions.stream().distinct().toList());
    }
    private String credentialsAction(ExternalIntegrationSystem system) {
        return switch (system) {
            case FI, TREASURY, BUDGETING, ONE_C, EXCHANGE -> "Запустите uzassets и задайте UZASSETS_INTEGRATION_USERNAME / UZASSETS_INTEGRATION_PASSWORD";
            case PRODUCTION, WMS, SCADA -> "Запустите mes и задайте API key / X-Role для интеграционного пользователя";
            case HR -> "Запустите hh и задайте HH_MM_SERVICE_TOKEN или HR_API_KEY";
            case EDO, ETTN -> "Запустите docflow и задайте DOCFLOW_MM_SERVICE_TOKEN или EDO_API_KEY";
            default -> "Проверьте credentials и health path интеграции";
        };
    }
    private String aggregate(List<String> statuses) {
        if (statuses.stream().anyMatch("FAIL"::equals)) {
            return "FAIL";
        }
        if (statuses.stream().anyMatch("WARN"::equals)) {
            return "WARN";
        }
        return "PASS";
    }
    private String worse(String current, String candidate) {
        if ("FAIL".equals(current) || "FAIL".equals(candidate)) {
            return "FAIL";
        }
        if ("WARN".equals(current) || "WARN".equals(candidate)) {
            return "WARN";
        }
        return "PASS";
    }
    private record Scenario(String suite, String suiteName, ExternalIntegrationSystem system, String eventType) {
    }
}


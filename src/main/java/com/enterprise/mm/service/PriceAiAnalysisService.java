package com.enterprise.mm.service;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ContractAiDtos.ContractAiFindingDto;
import com.enterprise.mm.payload.ContractAiDtos.ContractAiRecommendationDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptResponse;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeRequest;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeResponse;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiPricePointDto;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PriceAiAnalysisService {
    private static final Set<String> AI_PROVIDER_SYSTEMS = Set.of("CHATGPT", "CLAUDE", "GEMINI");
    private static final int DEFAULT_HISTORY_LIMIT = 30;
    private static final int MAX_HISTORY_LIMIT = 200;
    private final MaterialRepository materialRepository;
    private final SupplierRepository supplierRepository;
    private final PriceAiHistoryCollector historyCollector;
    private final ObjectProvider<IntegrationSettingService> integrationSettingServiceProvider;
    private final ContractAiJsonSupport jsonSupport;

    @Transactional(readOnly = true)
    public PriceAiAnalyzeResponse analyze(PriceAiAnalyzeRequest request) {
        if (request == null || request.materialId() == null) {
            throw new BadRequestException("ID материала обязателен для анализа цены");
        }
        Material material = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), request.materialId())
            .filter(this::belongsToCurrentScope)
            .orElseThrow(() -> new NotFoundException("Material not found: " + request.materialId()));
        Supplier supplier = request.supplierId() == null
            ? null
            : supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), request.supplierId())
                .filter(this::belongsToCurrentScope)
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + request.supplierId()));
        String provider = aiProvider(request.providerSystem());
        int limit = normalizedLimit(request.historyLimit());
        String currency = firstNonBlank(request.currencyCode(), "UZS").toUpperCase(Locale.ROOT);
        List<PriceAiPricePointDto> points = historyCollector.pricePoints(material.getId(), supplier == null ? null : supplier.getId(), request.contractId(), currency, limit);
        PriceAiHistoryCollector.PriceStats stats = historyCollector.stats(points);
        List<ContractAiFindingDto> ruleFindings = ruleFindings(request, material, supplier, stats);
        List<ContractAiRecommendationDto> ruleRecommendations = recommendations(ruleFindings, stats);
        Map<String, Object> context = context(request, material, supplier, stats, points, ruleFindings);
        AiPromptResponse ai = runAi(provider, context);
        List<ContractAiFindingDto> findings = jsonSupport.mergeFindings(ruleFindings, ai == null ? null : ai.response());
        List<ContractAiRecommendationDto> recommendations = jsonSupport.mergeRecommendations(ruleRecommendations, ai == null ? null : ai.response());
        return new PriceAiAnalyzeResponse(
            provider,
            ai == null ? "DISABLED" : ai.status(),
            ai == null ? "AI provider service is not available" : ai.message(),
            ai == null ? null : ai.model(),
            material.getId(),
            firstNonBlank(material.getName(), material.getCode()),
            request.proposedUnitPrice(),
            stats.minHistoricalPrice(),
            stats.maxHistoricalPrice(),
            stats.avgHistoricalPrice(),
            stats.lastPurchasePrice(),
            stats.quotationMinPrice(),
            percentDeviation(request.proposedUnitPrice(), stats.avgHistoricalPrice()),
            points,
            findings,
            recommendations,
            context,
            LocalDateTime.now()
        );
    }
    private List<ContractAiFindingDto> ruleFindings(PriceAiAnalyzeRequest request, Material material, Supplier supplier, PriceAiHistoryCollector.PriceStats stats) {
        List<ContractAiFindingDto> findings = new ArrayList<>();
        BigDecimal proposed = request.proposedUnitPrice();
        if (proposed == null) {
            findings.add(finding("INFO", "PRICE_INPUT", "Не указана проверяемая цена", "Введите предлагаемую цену за единицу, чтобы рассчитать отклонения от истории и стандартной стоимости."));
        }
        if (stats.avgHistoricalPrice() == null) {
            findings.add(finding("WARNING", "PRICE_HISTORY", "Нет истории цен", "Для материала не найдены цены в PO, КП, заявках или оценке запасов по текущей компании."));
        }
        if (supplier != null && "BLOCKED".equalsIgnoreCase(supplier.getStatus())) {
            findings.add(finding("CRITICAL", "SUPPLIER", "Поставщик заблокирован", "Анализируемая цена связана с заблокированным поставщиком."));
        }
        BigDecimal averageDeviation = percentDeviation(proposed, stats.avgHistoricalPrice());
        if (averageDeviation != null && averageDeviation.compareTo(BigDecimal.valueOf(30)) > 0) {
            findings.add(finding("WARNING", "PRICE_HISTORY", "Цена выше исторической средней", "Отклонение от средней истории: " + averageDeviation + "%."));
        } else if (averageDeviation != null && averageDeviation.compareTo(BigDecimal.valueOf(-30)) < 0) {
            findings.add(finding("INFO", "PRICE_HISTORY", "Цена заметно ниже исторической средней", "Проверьте качество, комплектацию, валюту и условия поставки; отклонение: " + averageDeviation + "%."));
        }
        if (proposed != null && stats.quotationMinPrice() != null && proposed.compareTo(stats.quotationMinPrice().multiply(BigDecimal.valueOf(1.10))) > 0) {
            findings.add(finding("WARNING", "RFQ", "Цена выше минимального КП", "Минимальное найденное КП: " + stats.quotationMinPrice() + ", предлагаемая цена: " + proposed + "."));
        }
        if (stats.minHistoricalPrice() != null && stats.maxHistoricalPrice() != null && stats.minHistoricalPrice().signum() > 0) {
            BigDecimal spread = stats.maxHistoricalPrice().subtract(stats.minHistoricalPrice())
                .multiply(BigDecimal.valueOf(100))
                .divide(stats.minHistoricalPrice(), 2, RoundingMode.HALF_UP);
            if (spread.compareTo(BigDecimal.valueOf(50)) > 0) {
                findings.add(finding("INFO", "PRICE_VOLATILITY", "Высокий разброс исторических цен", "Разброс между минимумом и максимумом истории: " + spread + "%."));
            }
        }
        if (findings.isEmpty()) {
            findings.add(finding("INFO", "PRICE_CONTROL", "Критичных отклонений по цене не найдено", "Предлагаемая цена находится в пределах стандартной стоимости и исторических данных."));
        }
        return findings;
    }
    private List<ContractAiRecommendationDto> recommendations(List<ContractAiFindingDto> findings, PriceAiHistoryCollector.PriceStats stats) {
        List<ContractAiRecommendationDto> out = new ArrayList<>();
        for (ContractAiFindingDto finding : findings) {
            switch (finding.category()) {
                case "PRICE" -> out.add(recommendation(priority(finding), "Согласовать или пересмотреть цену", "Цена отклоняется от стандартной стоимости; нужно обоснование закупщика или поставщика."));
                case "PRICE_HISTORY" -> out.add(recommendation(priority(finding), "Сравнить с последними закупками", "Историческая средняя цена является контрольной точкой для переговоров."));
                case "RFQ" -> out.add(recommendation("MEDIUM", "Выбрать или запросить более выгодное КП", "Найдено КП дешевле анализируемой цены."));
                case "PRICE_VOLATILITY" -> out.add(recommendation("LOW", "Расширить выборку цен", "Разброс цен требует проверки валюты, условий поставки и спецификации."));
                case "PRICE_INPUT" -> out.add(recommendation("LOW", "Заполнить предлагаемую цену", "Без цены невозможно рассчитать отклонения."));
                case "SUPPLIER" -> out.add(recommendation(priority(finding), "Проверить статус поставщика", "Операции с поставщиком должны соответствовать политике блокировок."));
                default -> {
                }
            }
        }
        if (stats.avgHistoricalPrice() == null) {
            out.add(recommendation("MEDIUM", "Провести RFQ или запросить рыночные КП", "В системе недостаточно истории для автоматического бенчмарка."));
        }
        if (out.isEmpty()) {
            out.add(recommendation("LOW", "Продолжить стандартный контроль цены", "Критичных действий по цене не требуется."));
        }
        return jsonSupport.distinctRecommendations(out);
    }
    private AiPromptResponse runAi(String provider, Map<String, Object> context) {
        IntegrationSettingService service = integrationSettingServiceProvider.getIfAvailable();
        if (service == null) {
            return null;
        }
        String prompt = """
            Ты senior ERP MM procurement price analyst. Проанализируй цену материала, историю PO/КП/заявок/оценок запасов и риски закупки.
            Верни только JSON {"findings":[{"severity":"CRITICAL|WARNING|INFO","category":"...","title":"...","details":"..."}],"recommendations":[{"priority":"HIGH|MEDIUM|LOW","action":"...","reason":"..."}]}.
            Не придумывай цены и документы, которых нет в context, не раскрывай секреты.
            """;
        try {
            return service.testAiProvider(ExternalIntegrationSystem.from(provider), new AiPromptRequest(prompt, context));
        } catch (RuntimeException ex) {
            return new AiPromptResponse(provider, provider, null, "AI_ERROR", ex.getMessage(), null, null, LocalDateTime.now());
        }
    }
    private Map<String, Object> context(
        PriceAiAnalyzeRequest request,
        Material material,
        Supplier supplier,
        PriceAiHistoryCollector.PriceStats stats,
        List<PriceAiPricePointDto> points,
        List<ContractAiFindingDto> findings
    ) {
        return map(
            "tenantId", TenantContext.currentTenant(),
            "companyId", CompanyContext.currentCompany(),
            "request", map(
                "materialId", request.materialId(),
                "supplierId", request.supplierId(),
                "contractId", request.contractId(),
                "proposedUnitPrice", request.proposedUnitPrice(),
                "quantity", request.quantity(),
                "currencyCode", firstNonBlank(request.currencyCode(), "UZS")
            ),
            "material", map(
                "id", material.getId(),
                "name", material.getName(),
                "article", material.getArticle(),
                "manufacturer", material.getManufacturer(),
                "valuationMethod", material.getValuationMethod(),
                "mxikCode", material.getMxikCode(),
                "tnvedCode", material.getTnvedCode()
            ),
            "supplier", supplier == null ? null : map(
                "id", supplier.getId(),
                "name", supplierName(supplier),
                "status", supplier.getStatus(),
                "rating", supplier.getRating(),
                "category", supplier.getCategory()
            ),
            "metrics", stats,
            "pricePoints", points,
            "ruleFindings", findings
        );
    }
    private boolean belongsToCurrentScope(BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId());
    }
    private BigDecimal percentDeviation(BigDecimal value, BigDecimal baseline) {
        if (value == null || baseline == null || baseline.signum() <= 0) {
            return null;
        }
        return value.subtract(baseline)
            .multiply(BigDecimal.valueOf(100))
            .divide(baseline, 2, RoundingMode.HALF_UP);
    }
    private int normalizedLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(limit, MAX_HISTORY_LIMIT);
    }
    private String aiProvider(String value) {
        String provider = firstNonBlank(value, "CHATGPT").replace('-', '_').toUpperCase(Locale.ROOT);
        if (!AI_PROVIDER_SYSTEMS.contains(provider)) {
            throw new BadRequestException("Для AI-анализа доступны только CHATGPT, CLAUDE или GEMINI");
        }
        return provider;
    }
    private String supplierName(Supplier supplier) {
        return supplier == null ? null : firstNonBlank(supplier.getLegalName(), supplier.getShortName(), supplier.getName(), supplier.getCode());
    }
    private ContractAiFindingDto finding(String severity, String category, String title, String details) {
        return new ContractAiFindingDto(severity, category, title, details);
    }
    private ContractAiRecommendationDto recommendation(String priority, String action, String reason) {
        return new ContractAiRecommendationDto(priority, action, reason);
    }
    private String priority(ContractAiFindingDto finding) {
        return "CRITICAL".equals(finding.severity()) ? "HIGH" : "WARNING".equals(finding.severity()) ? "MEDIUM" : "LOW";
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length - 1; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}


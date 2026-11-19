package com.enterprise.mm.service;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.payload.MaterialDedupDtos.MaterialDedupResult;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchAiRequest;
import com.enterprise.mm.payload.MarketPriceResearchDtos.MarketPriceResearchResult;
import com.enterprise.mm.payload.RfqComparisonDtos.RfqComparisonResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

@Service
public class HttpMmAiClient implements MmAiClient {
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RestTemplate restTemplate;
    private final RestTemplate inventoryRestTemplate;
    private final RestTemplate inventoryPollRestTemplate;
    private final Duration inventoryJobTimeout;
    private final RestTemplate receiptRestTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String receiptBaseUrl;
    private final String receiptApiKey;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public HttpMmAiClient(
        RestTemplateBuilder builder, String baseUrl, String apiKey, Duration connectTimeout, Duration analysisTimeout
    ) {
        this(builder, baseUrl, apiKey, connectTimeout, analysisTimeout, Duration.ofSeconds(120));
    }

    public HttpMmAiClient(
        RestTemplateBuilder builder, String baseUrl, String apiKey, Duration connectTimeout, Duration analysisTimeout,
        Duration receiptTimeout
    ) {
        this(builder, baseUrl, apiKey, connectTimeout, analysisTimeout, receiptTimeout, baseUrl, apiKey);
    }

    public HttpMmAiClient(
        RestTemplateBuilder builder, String baseUrl, String apiKey, Duration connectTimeout, Duration analysisTimeout,
        Duration receiptTimeout, String receiptBaseUrl, String receiptApiKey
    ) {
        this(builder, baseUrl, apiKey, connectTimeout, analysisTimeout, receiptTimeout,
            receiptBaseUrl, receiptApiKey, Duration.ofSeconds(350), Duration.ofMinutes(30));
    }

    public HttpMmAiClient(
        RestTemplateBuilder builder, String baseUrl, String apiKey, Duration connectTimeout, Duration analysisTimeout,
        Duration receiptTimeout, String receiptBaseUrl, String receiptApiKey, Duration inventoryTimeout
    ) {
        this(builder, baseUrl, apiKey, connectTimeout, analysisTimeout, receiptTimeout,
            receiptBaseUrl, receiptApiKey, inventoryTimeout, Duration.ofMinutes(30));
    }

    @Autowired
    public HttpMmAiClient(
        RestTemplateBuilder builder,
        @Value("${mm.ai.base-url:}") String baseUrl,
        @Value("${mm.ai.api-key:}") String apiKey,
        @DurationUnit(ChronoUnit.SECONDS) @Value("${mm.ai.connect-timeout:5s}") Duration connectTimeout,
        @DurationUnit(ChronoUnit.SECONDS) @Value("${mm.ai.analysis-timeout:180s}") Duration analysisTimeout,
        @DurationUnit(ChronoUnit.SECONDS) @Value("${mm.ai.receipt-advice.timeout:120s}") Duration receiptTimeout,
        @Value("${mm.ai.receipt-advice.base-url:${mm.ai.base-url:}}") String receiptBaseUrl,
        @Value("${mm.ai.receipt-advice.api-key:${mm.ai.api-key:}}") String receiptApiKey,
        @DurationUnit(ChronoUnit.SECONDS)
        @Value("${mm.ai.inventory-document-extraction.timeout:350s}") Duration inventoryTimeout,
        @Value("${mm.ai.inventory-document-extraction.job-timeout:30m}") Duration inventoryJobTimeout
    ) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.inventoryJobTimeout = inventoryJobTimeout;
        this.receiptBaseUrl = receiptBaseUrl == null ? "" : receiptBaseUrl.replaceAll("/+$", "");
        this.receiptApiKey = receiptApiKey == null ? "" : receiptApiKey.trim();
        this.restTemplate = builder
            .setConnectTimeout(connectTimeout)
            .setReadTimeout(analysisTimeout)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.inventoryRestTemplate = builder.setConnectTimeout(connectTimeout).setReadTimeout(inventoryTimeout).build();
        this.inventoryPollRestTemplate = builder.setConnectTimeout(connectTimeout).setReadTimeout(Duration.ofSeconds(20)).build();
        this.receiptRestTemplate = builder.setConnectTimeout(connectTimeout).setReadTimeout(receiptTimeout)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
    }

    private record ReceiptSuggestionRequest(UUID companyId, String tenantId, String language, JsonNode backendContext) {}

    @Override
    public JsonNode extractInventoryDocument(UUID analysisId,
        List<InventoryAiDraftService.SourceFile> files, String language) {
        return extractInventoryDocument(analysisId, files, language, 0);
    }

    @Override
    public JsonNode extractInventoryDocument(UUID analysisId,
        List<InventoryAiDraftService.SourceFile> files, String language, int commissionAnalysisVersion) {
        return extractInventoryDocument(analysisId, files, language, commissionAnalysisVersion, 0);
    }

    @Override
    public JsonNode extractInventoryDocument(UUID analysisId,
        List<InventoryAiDraftService.SourceFile> files, String language, int commissionAnalysisVersion,
        int orderAnalysisVersion) {
        return extractInventoryDocument(analysisId, files, language, commissionAnalysisVersion, orderAnalysisVersion, null);
    }

    @Override
    public JsonNode extractInventoryDocument(UUID analysisId,
        List<InventoryAiDraftService.SourceFile> files, String language, int commissionAnalysisVersion,
        int orderAnalysisVersion, InventoryOrderAnalysisScopeService.Scope scope) {
        if (!isConfigured()) throw new BusinessException("MM AI service is not configured");
        if (analysisId == null || files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Inventory document analysis requires files and an analysis ID");
        }
        if (commissionAnalysisVersion < 0 || commissionAnalysisVersion > 2)
            throw new IllegalArgumentException("Unsupported inventory commission analysis version");
        if (orderAnalysisVersion < 0 || orderAnalysisVersion > 2)
            throw new IllegalArgumentException("Unsupported inventory order analysis version");
        if (orderAnalysisVersion == 1 && commissionAnalysisVersion != 1)
            throw new IllegalArgumentException("Inventory order analysis requires commission analysis version 1");
        if (orderAnalysisVersion == 2 && (commissionAnalysisVersion != 2 || scope == null
            || !CompanyContext.currentCompany().equals(scope.companyId())))
            throw new IllegalArgumentException("Scoped inventory order analysis requires an authorized version 2 scope");
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        List<Map<String, String>> documents = files.stream().map(file -> Map.of(
            "fileId", file.snapshot().fileId().toString(),
            "fileName", file.snapshot().fileName(),
            "contentType", file.snapshot().contentType(),
            "sha256", file.snapshot().sha256())).toList();
        try {
            HttpHeaders metadataHeaders = new HttpHeaders();
            metadataHeaders.setContentType(MediaType.TEXT_PLAIN);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("analysisId", analysisId.toString());
            metadata.put("language", language == null ? "uz" : language);
            if (commissionAnalysisVersion > 0) metadata.put("commissionAnalysisVersion", commissionAnalysisVersion);
            if (orderAnalysisVersion > 0) metadata.put("orderAnalysisVersion", orderAnalysisVersion);
            if (scope != null) metadata.put("scope", scope);
            metadata.put("documents", documents);
            parts.add("metadata", new HttpEntity<>(objectMapper.writeValueAsString(metadata), metadataHeaders));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("Inventory analysis metadata cannot be serialized", ex);
        }
        for (InventoryAiDraftService.SourceFile file : files) {
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.parseMediaType(file.snapshot().contentType()));
            parts.add("files", new HttpEntity<>(new ByteArrayResource(file.bytes()) {
                @Override public String getFilename() { return file.snapshot().fileName(); }
            }, fileHeaders));
        }
        HttpHeaders headers = aiHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-Tenant-Id", TenantContext.currentTenant());
        headers.set("X-Company-Id", CompanyContext.currentCompany().toString());
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, language == null ? "uz" : language);
        try {
            JsonNode submitted = inventoryRestTemplate.exchange(baseUrl + "/v1/inventory-document-extraction/jobs",
                HttpMethod.POST, new HttpEntity<>(parts, headers), JsonNode.class).getBody();
            HttpHeaders pollHeaders = new HttpHeaders();
            pollHeaders.addAll(headers);
            pollHeaders.remove(HttpHeaders.CONTENT_TYPE);
            return awaitInventoryJob(analysisId, submitted, pollHeaders, orderAnalysisVersion == 2);
        } catch (RestClientResponseException ex) {
            throw aiServiceException(ex);
        }
    }

    private JsonNode awaitInventoryJob(UUID analysisId, JsonNode submitted, HttpHeaders headers, boolean versionTwo) {
        JsonNode job = requireInventoryJob(submitted, analysisId);
        URI statusUri = inventoryStatusUri(job.path("statusUrl").asText(), analysisId, versionTwo);
        long deadline = System.nanoTime() + inventoryJobTimeout.toNanos();
        while (true) {
            String status = job.path("status").asText();
            if ("COMPLETED".equals(status)) {
                JsonNode result = job.path("result");
                if (!result.isObject()) throw invalidInventoryJob("Completed AI inventory job has no result");
                return result;
            }
            if ("FAILED".equals(status)) {
                JsonNode error = job.path("error");
                String code = error.path("errorCode").asText("INVENTORY_EXTRACTION_FAILED");
                String message = error.path("message").asText("AI inventory extraction failed");
                throw new MmAiServiceException(0, code, message, null);
            }
            if (!Set.of("QUEUED", "RUNNING", "WAITING").contains(status))
                throw invalidInventoryJob("Unknown AI inventory job status");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new MmAiServiceException(504, "INVENTORY_EXTRACTION_TIMEOUT",
                "AI inventory job did not finish before the polling deadline", null);
            long intervalSeconds = job.path("pollAfterSeconds").canConvertToLong()
                ? job.path("pollAfterSeconds").asLong() : 2;
            long waitMillis = Math.min(Math.min(Math.max(1, intervalSeconds), 60) * 1000L,
                Math.max(1, remaining / 1_000_000L));
            try {
                Thread.sleep(Math.max(1, waitMillis));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MmAiServiceException(0, "INVENTORY_EXTRACTION_INTERRUPTED", "AI inventory polling was interrupted", null);
            }
            try {
                job = requireInventoryJob(inventoryPollRestTemplate.exchange(statusUri, HttpMethod.GET,
                    new HttpEntity<Void>(headers), JsonNode.class).getBody(), analysisId);
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() != 503) throw aiServiceException(ex);
            } catch (ResourceAccessException ignored) {
                // A transient poll failure does not require another document upload.
            }
        }
    }

    private JsonNode requireInventoryJob(JsonNode job, UUID analysisId) {
        if (job == null || !job.isObject() || !analysisId.toString().equals(job.path("analysisId").asText())
            || !job.path("statusUrl").isTextual() || job.path("statusUrl").asText().isBlank())
            throw invalidInventoryJob("AI inventory job response does not match the request");
        return job;
    }

    private URI inventoryStatusUri(String statusUrl, UUID analysisId, boolean versionTwo) {
        try {
            URI base = URI.create(baseUrl + "/");
            URI candidate = base.resolve(statusUrl);
            if (!base.getScheme().equalsIgnoreCase(candidate.getScheme())
                || !base.getHost().equalsIgnoreCase(candidate.getHost())
                || effectivePort(base) != effectivePort(candidate)
                || candidate.getUserInfo() != null || candidate.getFragment() != null
                || !candidate.getPath().endsWith("/v1/inventory-document-extraction/jobs/" + analysisId)
                || versionTwo && (candidate.getRawQuery() == null
                    || !java.util.Arrays.asList(candidate.getRawQuery().split("&")).contains("analysisVersion=2")))
                throw invalidInventoryJob("AI inventory status URL is not an authorized job URL");
            return candidate;
        } catch (IllegalArgumentException ex) {
            throw invalidInventoryJob("AI inventory status URL is invalid");
        }
    }

    private int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private MmAiServiceException invalidInventoryJob(String message) {
        return new MmAiServiceException(502, "INVALID_INVENTORY_JOB_RESPONSE", message, null);
    }

    @Override
    public JsonNode suggestReceiptAdvice(JsonNode backendContext, String language) {
        if (!isReceiptAdviceConfigured()) throw new BusinessException("MM AI receipt advice is not configured");
        String tenant = TenantContext.currentTenant();
        if (!TenantContext.valid(tenant)) throw new BusinessException("Invalid receipt tenant context");
        HttpHeaders headers = aiHeaders(receiptApiKey);
        headers.set("X-Tenant-Id", tenant);
        if (!headers.containsKey(REQUEST_ID_HEADER)) headers.set(REQUEST_ID_HEADER, UUID.randomUUID().toString());
        try {
            return receiptRestTemplate.exchange(receiptBaseUrl + "/v1/receipt-advice/suggest", HttpMethod.POST,
                new HttpEntity<>(new ReceiptSuggestionRequest(CompanyContext.currentCompany(), tenant, language, backendContext), headers),
                JsonNode.class).getBody();
        } catch (RestClientResponseException ex) {
            throw aiServiceException(ex);
        }
    }

    @Override
    public RfqComparisonResult compareRfqQuotations(AiAnalysisJobService.RfqQuotationComparisonAiRequest request, String language) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rfq", request.rfqId().toString());
        boolean hasContext = false;
        if (request.referenceCurrency() != null && !request.referenceCurrency().isBlank()) {
            body.put("referenceCurrency", request.referenceCurrency());
            hasContext = true;
        }
        if (request.includeTaxInComparison() != null) {
            body.put("includeTaxInComparison", request.includeTaxInComparison());
            hasContext = true;
        }
        if (request.exchangeRates() != null && !request.exchangeRates().isEmpty()) {
            body.put("exchangeRates", request.exchangeRates());
            hasContext = true;
        }
        if (hasContext) {
            body.put("language", language == null ? "ru" : language);
        }
        return post(
            "/v1/quotation-comparison/from-rfq",
            body,
            RfqComparisonResult.class
        );
    }

    @Override
    public MaterialDedupResult searchMaterialDuplicates(AiAnalysisJobService.MaterialDedupSearchAiRequest request, String language) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("materialId", request == null ? null : request.materialId());
        body.put("name", request == null ? null : request.name());
        body.put("departmentId", request == null ? null : request.departmentId());
        body.put("companyId", request == null ? null : request.companyId());
        body.put("materialGroupId", request == null ? null : request.materialGroupId());
        body.put("limit", request == null ? null : request.limit());
        body.put("includeInactive", request == null ? null : request.includeInactive());
        body.put("language", language == null ? "ru" : language);
        return post(
            "/v1/material-dedup/search-by-name",
            body,
            MaterialDedupResult.class
        );
    }

    @Override
    public MaterialDedupResult scanMaterialDuplicates(String language) {
        requireConfigured();
        return post(
            "/v1/material-dedup/scan-from-db",
            Map.of("language", language == null ? "ru" : language),
            MaterialDedupResult.class
        );
    }

    @Override
    public JsonNode analyzeNonMovingStock(AiAnalysisJobService.NonMovingStockAiRequest request, String language) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("materialGroupId", request == null ? null : request.materialGroupId());
        body.put("departmentId", request == null ? null : request.departmentId());
        body.put("warehouseId", request == null ? null : request.warehouseId());
        body.put("language", language == null ? "ru" : language);
        return post(
            "/v1/non-moving-stock/scan-from-db",
            body,
            JsonNode.class
        );
    }

    @Override
    public JsonNode recommendMxik(UUID materialId, String language) {
        requireConfigured();
        if (materialId == null) {
            throw new IllegalArgumentException("materialId is required for MXIK recommendation");
        }
        HttpHeaders headers = aiHeaders();
        headers.set("X-Tenant-Id", TenantContext.currentTenant());
        headers.set("X-Company-Id", CompanyContext.currentCompany().toString());
        headers.set(HttpHeaders.ACCEPT_LANGUAGE,
            language == null || language.isBlank() ? "uz" : language);
        try {
            return restTemplate.exchange(
                baseUrl + "/v1/mxik-recommendation/recommend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("materialId", materialId), headers),
                JsonNode.class
            ).getBody();
        } catch (RestClientResponseException ex) {
            throw aiServiceException(ex);
        }
    }

    @Override
    public JsonNode recommendMaterialStockAccount(JsonNode context, String language) {
        requireConfigured();
        HttpHeaders headers = aiHeaders();
        headers.set("X-Tenant-Id", TenantContext.currentTenant());
        headers.set("X-Company-Id", CompanyContext.currentCompany().toString());
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, language == null || language.isBlank() ? "uz" : language);
        try {
            return restTemplate.exchange(baseUrl + "/v1/stock-account-recommendation/recommend",
                HttpMethod.POST, new HttpEntity<>(context, headers), JsonNode.class).getBody();
        } catch (RestClientResponseException ex) {
            throw aiServiceException(ex);
        }
    }

    @Override
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !apiKey.isBlank();
    }

    @Override
    public boolean isReceiptAdviceConfigured() {
        return !receiptBaseUrl.isBlank() && !receiptApiKey.isBlank();
    }

    @Override
    public MarketPriceResearchResult researchMarketPrice(MarketPriceResearchAiRequest request, String language) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("materialId", request == null ? null : request.materialId());
        if (request != null && request.currencyCode() != null && !request.currencyCode().isBlank()) {
            body.put("currencyCode", request.currencyCode());
        }
        if (request != null && request.quantity() != null && !request.quantity().isBlank()) {
            body.put("quantity", request.quantity());
        }
        if (request != null && request.materialName() != null && !request.materialName().isBlank()) {
            body.put("materialName", request.materialName());
        }
        if (request != null && request.manufacturer() != null && !request.manufacturer().isBlank()) {
            body.put("manufacturer", request.manufacturer());
        }
        if (request != null && request.article() != null && !request.article().isBlank()) {
            body.put("article", request.article());
        }
        if (request != null && request.unitOfMeasureCode() != null && !request.unitOfMeasureCode().isBlank()) {
            body.put("unitOfMeasureCode", request.unitOfMeasureCode());
        }
        if (request != null && request.includeLocal() != null) {
            body.put("includeLocal", request.includeLocal());
        }
        if (request != null && request.includeGlobal() != null) {
            body.put("includeGlobal", request.includeGlobal());
        }
        if (request != null && request.maxOffers() != null) {
            body.put("maxOffers", request.maxOffers());
        }
        if (language != null && !language.isBlank()) {
            body.put("language", language);
        }
        JsonNode response = post(
            "/v1/market-price/research",
            body,
            JsonNode.class
        );
        return marketPriceResearchResult(response);
    }

    @Override
    public JsonNode copilotMessage(JsonNode request) {
        requireConfigured();
        try {
            HttpHeaders headers = aiHeaders();
            headers.set("X-Tenant-Id", TenantContext.currentTenant());
            headers.set("X-Company-Id", CompanyContext.currentCompany().toString());
            return restTemplate.exchange(baseUrl + "/v1/copilot/messages", HttpMethod.POST,
                new HttpEntity<>(request, headers), JsonNode.class).getBody();
        } catch (RestClientResponseException ex) {
            throw aiServiceException(ex);
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            throw new MmAiServiceException(503, "COPILOT_TRANSPORT_ERROR", "AI connection interrupted; resume the same run", null);
        }
    }

    @Override
    public JsonNode diagnostics() {
        requireConfigured();
        return restTemplate.exchange(
            baseUrl + "/v1/health/diagnostics",
            HttpMethod.GET,
            new HttpEntity<>(aiHeaders()),
            JsonNode.class
        ).getBody();
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        try {
            return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(body, aiHeaders()),
                responseType
            ).getBody();
        } catch (RestClientResponseException ex) {
            throw aiServiceException(ex);
        }
    }

    private MarketPriceResearchResult marketPriceResearchResult(JsonNode response) {
        if (response == null || response.isNull()) {
            return null;
        }
        JsonNode payload = marketPricePayload(response);
        payload = normalizeMarketPriceResearchPayload(payload);
        try {
            return objectMapper.convertValue(payload, MarketPriceResearchResult.class);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("MM AI market price response format is invalid: " + ex.getMessage());
        }
    }

    private JsonNode marketPricePayload(JsonNode response) {
        JsonNode payload = response.path("data").isObject() ? response.path("data") : response;
        return payload.path("result").isObject() ? payload.path("result") : payload;
    }

    private JsonNode normalizeMarketPriceResearchPayload(JsonNode payload) {
        if (!payload.isObject()) {
            return payload;
        }
        ObjectNode source = payload.deepCopy();
        ObjectNode normalized = source.deepCopy();
        copyFirstIfMissing(normalized, "status", source, "status");
        copyFirstIfMissing(normalized, "recommendedUnitPrice", source, "recommendedUnitPrice", "recommended_unit_price", "suggestedUnitPrice", "suggested_unit_price");
        copyFirstIfMissing(normalized, "suggestedUnitPrice", source, "suggestedUnitPrice", "suggested_unit_price", "recommendedUnitPrice", "recommended_unit_price");
        copyFirstIfMissing(normalized, "recommendedPriceReliability", source, "recommendedPriceReliability", "recommended_price_reliability");
        copyFirstIfMissing(normalized, "recommendedPriceMethod", source, "recommendedPriceMethod", "recommended_price_method");
        copyFirstIfMissing(normalized, "recommendedTotalPrice", source, "recommendedTotalPrice", "recommended_total_price");
        copyFirstIfMissing(normalized, "currencyCode", source, "currencyCode", "currency_code");
        copyFirstIfMissing(normalized, "priceBasis", source, "priceBasis", "price_basis");
        copyFirstIfMissing(normalized, "unitConfidence", source, "unitConfidence", "unit_confidence");
        copyFirstIfMissing(normalized, "unitOfMeasureCode", source, "unitOfMeasureCode", "unit_of_measure_code");
        copyFirstIfMissing(normalized, "generatedAt", source, "generatedAt", "generated_at");
        copyFirstIfMissing(normalized, "llmUsed", source, "llmUsed", "llm_used");
        copyFirstIfMissing(normalized, "marketPriceDraft", source, "marketPriceDraft", "market_price_draft");
        copyFirstIfMissing(normalized, "offerSummary", source, "offerSummary", "offer_summary");
        copyFirstIfMissing(normalized, "confidence", source, "confidence");
        normalized.set("market", marketStatistics(source));
        if (source.path("offers").isArray()) {
            normalized.set("offers", marketOffers(source.path("offers"), text(source, "currencyCode", "currency_code")));
        }
        ArrayNode rejectedOffers = rejectedOffers(source, text(source, "currencyCode", "currency_code"));
        if (rejectedOffers.size() > 0) {
            normalized.set("rejectedOffers", rejectedOffers);
        }
        JsonNode internalComparison = internalComparison(source.path("internalComparison").isObject()
            ? source.path("internalComparison")
            : source.path("internal_comparison"));
        if (internalComparison != null) {
            normalized.set("internalComparison", internalComparison);
        }
        if (source.path("findings").isArray()) {
            normalized.set("findings", marketFindings(source.path("findings")));
        }
        return normalized;
    }

    private void copyFirst(ObjectNode target, String targetName, ObjectNode source, String... sourceNames) {
        for (String sourceName : sourceNames) {
            JsonNode value = source.path(sourceName);
            if (!value.isMissingNode() && !value.isNull()) {
                target.set(targetName, value);
                return;
            }
        }
    }

    private void copyFirstIfMissing(ObjectNode target, String targetName, ObjectNode source, String... sourceNames) {
        JsonNode existing = target.path(targetName);
        if (!existing.isMissingNode() && !existing.isNull() && !existing.asText().isBlank()) {
            return;
        }
        copyFirst(target, targetName, source, sourceNames);
    }

    private ObjectNode marketStatistics(ObjectNode source) {
        JsonNode existing = source.path("market");
        ObjectNode market = existing.isObject() ? existing.deepCopy() : objectMapper.createObjectNode();
        copyFirstIfMissing(market, "median", source, "marketMedian", "market_median");
        copyFirstIfMissing(market, "average", source, "marketAverage", "market_average");
        copyFirstIfMissing(market, "min", source, "marketMin", "market_min");
        copyFirstIfMissing(market, "max", source, "marketMax", "market_max");
        copyFirstIfMissing(market, "sampleSize", source, "sampleSize", "sample_size");
        copyFirstIfMissing(market, "outliersRemoved", source, "outliersRemoved", "outliers_removed");
        copyFirstIfMissing(market, "spreadRatio", source, "spreadRatio", "spread_ratio");
        return market;
    }

    private ArrayNode marketOffers(JsonNode offers, String resultCurrencyCode) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!offers.isArray()) {
            return result;
        }
        offers.forEach(offer -> {
            if (offer.isObject()) {
                result.add(fullOffer(offer, resultCurrencyCode));
            }
        });
        return result;
    }

    private ArrayNode comparableOffers(JsonNode offers, String resultCurrencyCode) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!offers.isArray()) {
            return result;
        }
        List<JsonNode> comparable = new ArrayList<>();
        offers.forEach(offer -> {
            if (isComparableOffer(offer)) {
                comparable.add(offer);
            }
        });
        comparable.sort(Comparator
            .comparing((JsonNode offer) -> relevanceValue(relevanceNode(offer)), Comparator.nullsLast(Comparator.reverseOrder())));

        Set<String> seen = new HashSet<>();
        for (JsonNode offer : comparable) {
            ObjectNode compact = objectMapper.createObjectNode();
            copyFirst(compact, "title", offerAsObject(offer), "title", "name", "productName", "product_name", "sourceTitle", "source_title");
            copyFirst(compact, "source", offerAsObject(offer), "source", "seller", "sellerName", "seller_name", "supplierName", "supplier_name", "domain");
            copyFirst(compact, "sourceUrl", offerAsObject(offer), "sourceUrl", "source_url", "url", "link", "sourceLink", "source_link");
            JsonNode unitPrice = offerPrice(offer);
            if (unitPrice != null) {
                compact.set("unitPrice", unitPrice);
            }
            boolean normalizedPrice = normalizedOfferPrice(offer);
            if (normalizedPrice && resultCurrencyCode != null && !resultCurrencyCode.isBlank()) {
                compact.put("currencyCode", resultCurrencyCode);
            } else {
                JsonNode currency = firstNode(offer, "currencyCode", "currency_code", "normalizedCurrencyCode", "normalized_currency_code", "convertedCurrencyCode", "converted_currency_code", "currency");
                if (currency != null && !currency.isNull()) {
                    compact.set("currencyCode", currency);
                } else if (resultCurrencyCode != null && !resultCurrencyCode.isBlank()) {
                    compact.put("currencyCode", resultCurrencyCode);
                }
            }
            JsonNode relevance = normalizedRelevance(relevanceNode(offer));
            if (relevance != null) {
                compact.set("relevance", relevance);
            }
            String key = offerKey(compact);
            if (seen.add(key)) {
                result.add(compact);
            }
            if (result.size() == 5) {
                break;
            }
        }
        return result;
    }

    private ArrayNode rejectedOffers(ObjectNode source, String resultCurrencyCode) {
        ArrayNode result = objectMapper.createArrayNode();
        appendRejectedOffers(result, source.path("rejectedOffers"), resultCurrencyCode, false);
        appendRejectedOffers(result, source.path("rejected_offers"), resultCurrencyCode, false);
        appendRejectedOffers(result, source.path("excludedOffers"), resultCurrencyCode, true);
        appendRejectedOffers(result, source.path("excluded_offers"), resultCurrencyCode, true);
        appendRejectedOffers(result, source.path("candidates"), resultCurrencyCode, false);
        appendRejectedOffers(result, source.path("offers"), resultCurrencyCode, true);
        List<JsonNode> sorted = new ArrayList<>();
        result.forEach(sorted::add);
        sorted.sort(Comparator.comparing(
            (JsonNode offer) -> relevanceValue(relevanceNode(offer)),
            Comparator.nullsLast(Comparator.reverseOrder())));
        return deduplicateAndLimit(objectMapper.createArrayNode().addAll(sorted));
    }

    private void appendRejectedOffers(ArrayNode target, JsonNode offers, String resultCurrencyCode, boolean requireRejected) {
        if (!offers.isArray()) {
            return;
        }
        offers.forEach(offer -> {
            if (!offer.isObject() || !hasRejectedOfferEvidence(offer)) {
                return;
            }
            boolean rejected = rejectedOffer(offer);
            if (requireRejected && !rejected) {
                return;
            }
            ObjectNode compact = compactOffer(offer, resultCurrencyCode);
            compact.put("excluded", rejected);
            String reason = text(offer, "exclusionReason", "exclusion_reason", "excludeReason", "exclude_reason", "reason", "rejectionReason", "rejection_reason");
            if (reason != null && !reason.isBlank()) {
                compact.put("exclusionReason", reason);
            }
            target.add(compact);
        });
    }

    private boolean rejectedOffer(JsonNode offer) {
        if (offer.path("excluded").asBoolean(false)) {
            return true;
        }
        BigDecimal relevance = relevanceValue(relevanceNode(offer));
        return relevance != null && relevance.compareTo(new BigDecimal("0.50")) < 0;
    }

    private boolean hasRejectedOfferEvidence(JsonNode offer) {
        return offerEvidenceNode(offer) != null || offerPrice(offer) != null;
    }

    private ObjectNode fullOffer(JsonNode offer, String resultCurrencyCode) {
        // Emit one canonical property per field: alias duplicates can overwrite
        // normalized values or cause Jackson to mutate an already-created record.
        ObjectNode full = objectMapper.createObjectNode();
        copyFirstIfMissing(full, "title", offerAsObject(offer), "title", "name", "productName", "product_name", "sourceTitle", "source_title");
        copyFirstIfMissing(full, "source", offerAsObject(offer), "source", "seller", "sellerName", "seller_name", "supplierName", "supplier_name", "domain");
        copyFirstIfMissing(full, "sourceUrl", offerAsObject(offer), "sourceUrl", "source_url", "url", "link", "sourceLink", "source_link");
        JsonNode unitPrice = offerPrice(offer);
        JsonNode originalPrice = firstNode(offer, "unitPrice", "unit_price");
        if (originalPrice != null || unitPrice != null) {
            full.set("unitPrice", originalPrice != null ? originalPrice : unitPrice);
        }
        copyFirstIfMissing(full, "convertedUnitPrice", offerAsObject(offer), "convertedUnitPrice", "converted_unit_price", "convertedPrice", "converted_price", "normalizedUnitPrice", "normalized_unit_price", "normalizedPrice", "normalized_price", "normalizedAmount", "normalized_amount");
        copyFirstIfMissing(full, "rawPrice", offerAsObject(offer), "rawPrice", "raw_price");
        copyFirstIfMissing(full, "fxRate", offerAsObject(offer), "fxRate", "fx_rate", "exchangeRate", "exchange_rate");
        copyFirstIfMissing(full, "fxSource", offerAsObject(offer), "fxSource", "fx_source", "fxRateSource", "fx_rate_source", "exchangeRateSource", "exchange_rate_source");
        copyFirstIfMissing(full, "unitOfMeasureCode", offerAsObject(offer), "unitOfMeasureCode", "unit_of_measure_code");
        copyFirstIfMissing(full, "unitMatch", offerAsObject(offer), "unitMatch", "unit_match");
        copyFirstIfMissing(full, "observedAt", offerAsObject(offer), "observedAt", "observed_at");
        copyFirstIfMissing(full, "sourceKind", offerAsObject(offer), "sourceKind", "source_kind");
        JsonNode relevance = normalizedRelevance(relevanceNode(offer));
        if (relevance != null) {
            full.set("relevance", relevance);
        }
        copyFirst(full, "matched", offerAsObject(offer), "matched");
        copyFirst(full, "excluded", offerAsObject(offer), "excluded");
        copyFirstIfMissing(full, "basis", offerAsObject(offer), "basis", "priceBasis", "price_basis");
        copyFirstIfMissing(full, "extraction", offerAsObject(offer), "extraction", "extractionType", "extraction_type");
        copyFirstIfMissing(full, "usedInStatistics", offerAsObject(offer), "usedInStatistics", "used_in_statistics");
        copyFirstIfMissing(full, "excludeReason", offerAsObject(offer), "excludeReason", "exclude_reason", "exclusionReason", "exclusion_reason", "reason");
        if (originalPrice != null) {
            copyFirst(full, "currencyCode", offerAsObject(offer), "currencyCode", "currency_code", "currency");
        }
        copyOfferCurrency(full, offer, resultCurrencyCode);
        return full;
    }

    private ObjectNode compactOffer(JsonNode offer, String resultCurrencyCode) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyFirst(compact, "title", offerAsObject(offer), "title", "name", "productName", "product_name", "sourceTitle", "source_title");
        copyFirst(compact, "source", offerAsObject(offer), "source", "seller", "sellerName", "seller_name", "supplierName", "supplier_name", "domain");
        copyFirst(compact, "sourceUrl", offerAsObject(offer), "sourceUrl", "source_url", "url", "link", "sourceLink", "source_link");
        JsonNode unitPrice = offerPrice(offer);
        if (unitPrice != null) {
            compact.set("unitPrice", unitPrice);
        }
        copyOfferCurrency(compact, offer, resultCurrencyCode);
        JsonNode relevance = normalizedRelevance(relevanceNode(offer));
        if (relevance != null) {
            compact.set("relevance", relevance);
        }
        return compact;
    }

    private void copyOfferCurrency(ObjectNode compact, JsonNode offer, String resultCurrencyCode) {
        if (compact.hasNonNull("currencyCode")) {
            return;
        }
        boolean normalizedPrice = normalizedOfferPrice(offer);
        if (normalizedPrice && resultCurrencyCode != null && !resultCurrencyCode.isBlank()) {
            compact.put("currencyCode", resultCurrencyCode);
            return;
        }
        JsonNode currency = firstNode(offer, "currencyCode", "currency_code", "normalizedCurrencyCode", "normalized_currency_code", "convertedCurrencyCode", "converted_currency_code", "currency");
        if (currency != null && !currency.isNull()) {
            compact.set("currencyCode", currency);
        } else if (resultCurrencyCode != null && !resultCurrencyCode.isBlank()) {
            compact.put("currencyCode", resultCurrencyCode);
        }
    }

    private ArrayNode deduplicateAndLimit(ArrayNode offers) {
        ArrayNode result = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode offer : offers) {
            ObjectNode compact = offerAsObject(offer);
            if (seen.add(offerKey(compact))) {
                result.add(compact);
            }
            if (result.size() == 5) {
                break;
            }
        }
        return result;
    }

    private boolean isComparableOffer(JsonNode offer) {
        if (!offer.isObject()) {
            return false;
        }
        if (offer.path("excluded").asBoolean(false)) {
            return false;
        }
        BigDecimal relevance = relevanceValue(relevanceNode(offer));
        if (relevance != null && relevance.compareTo(new BigDecimal("0.50")) < 0) {
            return false;
        }
        return offerPrice(offer) != null || offerEvidenceNode(offer) != null;
    }

    private JsonNode offerPrice(JsonNode offer) {
        JsonNode direct = firstNode(
            offer,
            "convertedUnitPrice",
            "converted_unit_price",
            "convertedPrice",
            "converted_price",
            "normalizedUnitPrice",
            "normalized_unit_price",
            "normalizedPrice",
            "normalized_price",
            "normalizedAmount",
            "normalized_amount",
            "unitPrice",
            "unit_price",
            "unitPriceUzs",
            "unit_price_uzs",
            "priceUzs",
            "price_uzs",
            "priceValue",
            "price_value",
            "amount",
            "value",
            "price"
        );
        if (direct != null) {
            return direct;
        }
        JsonNode price = offer.path("price");
        if (price.isObject()) {
            return firstNode(price, "unitPrice", "unit_price", "amount", "value");
        }
        return null;
    }

    private boolean normalizedOfferPrice(JsonNode offer) {
        return firstNode(
            offer,
            "convertedUnitPrice",
            "converted_unit_price",
            "convertedPrice",
            "converted_price",
            "normalizedUnitPrice",
            "normalized_unit_price",
            "normalizedPrice",
            "normalized_price",
            "normalizedAmount",
            "normalized_amount",
            "unitPriceUzs",
            "unit_price_uzs",
            "priceUzs",
            "price_uzs"
        ) != null;
    }

    private JsonNode relevanceNode(JsonNode offer) {
        return firstNode(offer, "relevance", "score", "relevanceScore", "relevance_score", "confidenceScore", "confidence_score");
    }

    private JsonNode offerEvidenceNode(JsonNode offer) {
        return firstNode(
            offer,
            "title",
            "name",
            "productName",
            "product_name",
            "sourceTitle",
            "source_title",
            "source",
            "seller",
            "sellerName",
            "seller_name",
            "supplierName",
            "supplier_name",
            "domain",
            "sourceUrl",
            "source_url",
            "url",
            "link",
            "sourceLink",
            "source_link"
        );
    }

    private ObjectNode offerAsObject(JsonNode offer) {
        return offer.isObject() ? (ObjectNode) offer : objectMapper.createObjectNode();
    }

    private String offerKey(ObjectNode offer) {
        String sourceUrl = text(offer, "sourceUrl");
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            return "url:" + sourceUrl.trim().toLowerCase();
        }
        return "offer:%s:%s:%s".formatted(
            text(offer, "source"),
            text(offer, "title"),
            text(offer, "unitPrice")
        ).toLowerCase();
    }

    private JsonNode internalComparison(JsonNode source) {
        if (!source.isObject()) {
            return null;
        }
        ObjectNode normalized = source.deepCopy();
        copyFirstIfMissing(normalized, "sampleSize", (ObjectNode) source, "sampleSize", "sample_size", "sourceCount", "source_count");
        copyFirstIfMissing(normalized, "averageHistoricalPrice", (ObjectNode) source, "averageHistoricalPrice", "average_historical_price", "averageInternalUnitPrice", "average_internal_unit_price");
        copyFirstIfMissing(normalized, "lastPurchasePrice", (ObjectNode) source, "lastPurchasePrice", "last_purchase_price", "latestInternalUnitPrice", "latest_internal_unit_price");
        copyFirstIfMissing(normalized, "currencyCode", (ObjectNode) source, "currencyCode", "currency_code");
        copyFirstIfMissing(normalized, "marketVsInternalPercent", (ObjectNode) source, "marketVsInternalPercent", "market_vs_internal_percent", "variancePercent", "variance_percent");
        return normalized;
    }

    private ArrayNode marketFindings(JsonNode findings) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!findings.isArray()) {
            return result;
        }
        findings.forEach(finding -> {
            if (!finding.isObject()) {
                return;
            }
            ObjectNode full = finding.deepCopy();
            copyFirstIfMissing(full, "category", offerAsObject(finding), "category", "code");
            String message = text(finding, "message", "details", "title", "recommendation");
            if (!full.hasNonNull("message") && message != null && !message.isBlank()) {
                full.put("message", message);
            }
            result.add(full);
        });
        return result;
    }

    private ArrayNode businessFindings(JsonNode findings) {
        ArrayNode result = objectMapper.createArrayNode();
        if (!findings.isArray()) {
            return result;
        }
        List<JsonNode> businessFindings = new ArrayList<>();
        findings.forEach(finding -> {
            if (isBusinessFinding(finding)) {
                businessFindings.add(finding);
            }
        });
        businessFindings.sort(Comparator.comparingInt(this::findingRank));
        for (JsonNode finding : businessFindings) {
            ObjectNode compact = objectMapper.createObjectNode();
            copyFirst(compact, "severity", offerAsObject(finding), "severity");
            copyFirst(compact, "category", offerAsObject(finding), "category", "code");
            String message = text(finding, "message", "details", "title", "recommendation");
            if (message != null && !message.isBlank()) {
                compact.put("message", message);
            }
            result.add(compact);
            if (result.size() == 3) {
                break;
            }
        }
        return result;
    }

    private boolean isBusinessFinding(JsonNode finding) {
        if (!finding.isObject()) {
            return false;
        }
        String category = text(finding, "category", "code");
        if (category == null) {
            return true;
        }
        return switch (category.trim().toUpperCase()) {
            case "LLM_USED", "SEARCH_QUERY_COUNT", "OFFER_LIMIT", "OFFER_SAMPLE" -> false;
            default -> true;
        };
    }

    private int findingRank(JsonNode finding) {
        String severity = text(finding, "severity");
        if (severity == null) {
            return 3;
        }
        return switch (severity.trim().toUpperCase()) {
            case "ERROR", "CRITICAL", "BLOCKER" -> 0;
            case "WARNING" -> 1;
            case "INFO" -> 2;
            default -> 3;
        };
    }

    private JsonNode firstNode(JsonNode source, String... names) {
        for (String name : names) {
            JsonNode value = source.path(name);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode source, String... names) {
        JsonNode value = firstNode(source, names);
        return value == null ? null : value.asText();
    }

    private BigDecimal relevanceValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            BigDecimal relevance = new BigDecimal(value.asText());
            return relevance.compareTo(BigDecimal.ONE) > 0
                ? relevance.divide(new BigDecimal("100"))
                : relevance;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private JsonNode normalizedRelevance(JsonNode value) {
        BigDecimal relevance = relevanceValue(value);
        return relevance == null ? null : objectMapper.valueToTree(relevance);
    }

    private MmAiServiceException aiServiceException(RestClientResponseException ex) {
        JsonNode body = parseErrorBody(ex.getResponseBodyAsString());
        JsonNode error = body == null ? null : body.path("error");
        String code = firstText(
            error == null ? null : error.path("errorCode"),
            error == null ? null : error.path("code"),
            body == null ? null : body.path("errorCode"),
            body == null ? null : body.path("code")
        );
        String message = firstText(
            error == null ? null : error.path("message"),
            body == null ? null : body.path("message")
        );
        String requestId = firstText(
            error == null ? null : error.path("requestId"),
            body == null ? null : body.path("requestId"),
            body == null ? null : body.path("correlationId")
        );
        if (requestId == null || requestId.isBlank()) {
            requestId = ex.getResponseHeaders() == null ? null : ex.getResponseHeaders().getFirst(REQUEST_ID_HEADER);
        }
        return new MmAiServiceException(
            ex.getStatusCode().value(),
            code,
            message == null || message.isBlank() ? ex.getStatusText() : message,
            requestId,
            retryAfterSeconds(ex)
        );
    }

    private Integer retryAfterSeconds(RestClientResponseException ex) {
        String value = ex.getResponseHeaders() == null ? null : ex.getResponseHeaders().getFirst("Retry-After");
        try {
            return value == null ? null : Math.max(1, Math.min(60, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private JsonNode parseErrorBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }

    private HttpHeaders aiHeaders() {
        return aiHeaders(apiKey);
    }

    private HttpHeaders aiHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!key.isBlank()) {
            headers.set(API_KEY_HEADER, key);
        }
        String requestId = MDC.get("requestId");
        if (requestId != null && !requestId.isBlank()) {
            headers.set(REQUEST_ID_HEADER, requestId);
        }
        return headers;
    }

    private void requireConfigured() {
        if (baseUrl.isBlank()) {
            throw new BusinessException("MM AI service is not configured");
        }
    }
}

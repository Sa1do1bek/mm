package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ProductCodeSyncDtos.ProductCodeSyncRequest;
import com.enterprise.mm.payload.ProductCodeSyncDtos.ProductCodeSyncResult;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.entity.IkpuCode;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.TnvedCode;
import com.enterprise.mm.repository.IkpuCodeRepository;
import com.enterprise.mm.repository.MXIKRepository;
import com.enterprise.mm.repository.TnvedCodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductCodeSyncService {
    private static final String TASNIF_BASE_URL = "https://tasnif.soliq.uz/api/cls-api";
    private static final String TASNIF_SEARCH_PATH = TASNIF_BASE_URL + "/elasticsearch/search?search=&lang=ru";
    private static final String CUSTOMS_TNVED_PATH = "https://tarif.customs.uz/hscode-list";
    private static final String CUSTOMS_SOURCE_SYSTEM = "CUSTOMS";
    private static final String IKPU_DEFAULT_PATH = TASNIF_SEARCH_PATH;
    private static final String TNVED_DEFAULT_PATH = CUSTOMS_TNVED_PATH;
    private static final int DEFAULT_PAGE_SIZE = 1000;
    private static final int DEFAULT_MAX_PAGES = 10000;
    private static final int MAX_PAGE_SIZE = 5000;
    private static final int MAX_ENTITY_NAME_LENGTH = 240;
    private static final int CUSTOMS_TNVED_PAGE_SIZE = 100;
    private static final int CUSTOMS_TNVED_MAX_PAGE_SIZE = 250;
    private final IkpuCodeRepository ikpuCodeRepository;
    private final MXIKRepository mxikRepository;
    private final TnvedCodeRepository tnvedCodeRepository;
    private final IntegrationSettingService integrationSettingService;
    private final IntegrationEventService integrationEventService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    // Customs publishes a public read-only catalogue with a chain that is not trusted by stock JDKs in dev.
    // Keep this client isolated from credentialed integrations.
    private final HttpClient customsHttpClient = customsHttpClient();

    @Transactional
    public ProductCodeSyncResult syncIkpu(ProductCodeSyncRequest request) {
        LocalDateTime now = LocalDateTime.now();
        SyncSource source = sourceRows(request, ProductCodeDictionary.IKPU, mockIkpuRows());
        SyncCounter counter = new SyncCounter();
        for (JsonNode row : source.rows()) {
            String code = normalize(firstText(row, "ikpuCode", "ikpu", "mxikCode", "mxik", "classCode", "class_code", "code"));
            if (code == null) {
                continue;
            }
            IkpuCode item = ikpuCodeRepository.findByTenantIdAndCompanyIdAndIkpuCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), code)
                .orElseGet(() -> {
                    counter.created++;
                    return new IkpuCode();
                });
            if (item.getId() != null) {
                counter.updated++;
            }
            item.setCode(code);
            item.setIkpuCode(code);
            item.setName(multilingualName(
                row,
                entityName(firstNonBlank(firstText(row, "name", "nameRu", "title", "productName", "fullName", "label", "mxikName"), "Product Code Item"), "Product Code Item")
            ));
            item.setDescription(description(row));
            item.setStatus(status(row));
            item.setPackageCode(normalize(firstText(row, "packageCode", "package_code", "package")));
            item.setUnitCode(normalize(firstText(row, "unitCode", "unit_code", "uom", "categoryUnitName", "unitsName", "recommendedUnitsName")));
            item.setGroupCode(normalize(firstText(row, "groupCode", "group_code", "group")));
            item.setVatRate(decimal(row, "vatRate", "vat_rate", "ndsRate", "rateVat"));
            item.setService(bool(row, "service", "isService", "is_service"));
            item.setSourceSystem(source.sourceSystem());
            item.setExternalId(firstText(row, "externalId", "id", "guid"));
            item.setLastSyncedAt(now);
            ikpuCodeRepository.save(item);
            upsertMxik(row, code);
            upsertTnvedFromLinkedRow(row, now);
        }
        ProductCodeSyncResult result = new ProductCodeSyncResult(
            "IKPU",
            source.sourceSystem(),
            source.mode(),
            source.rows().size(),
            counter.created,
            counter.updated,
            source.pagesFetched(),
            source.complete(),
            source.message(),
            now
        );
        integrationEventService.emit("SOLIQ.IKPU_CODES_SYNCED", "IKPU_CODE", null, payload(result, request));
        return result;
    }
    @Transactional
    public ProductCodeSyncResult syncMxik(ProductCodeSyncRequest request) {
        ProductCodeSyncResult result = syncIkpu(request);
        ProductCodeSyncResult mxikResult = new ProductCodeSyncResult(
            "MXIK",
            result.sourceSystem(),
            result.mode(),
            result.received(),
            result.created(),
            result.updated(),
            result.pagesFetched(),
            result.complete(),
            result.message(),
            result.syncedAt()
        );
        integrationEventService.emit("SOLIQ.MXIK_CODES_SYNCED", "MXIK_CODE", null, payload(mxikResult, request));
        return mxikResult;
    }
    @Transactional
    public ProductCodeSyncResult syncTnved(ProductCodeSyncRequest request) {
        LocalDateTime now = LocalDateTime.now();
        SyncSource source = sourceRows(request, ProductCodeDictionary.TNVED, mockTnvedRows());
        SyncCounter counter = new SyncCounter();
        for (JsonNode row : source.rows()) {
            String code = tnvedCode(row);
            if (code == null) {
                continue;
            }
            TnvedCode item = tnvedCodeRepository.findByTenantIdAndCompanyIdAndTnvedCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), code)
                .orElseGet(() -> {
                    counter.created++;
                    return new TnvedCode();
                });
            if (item.getId() != null) {
                counter.updated++;
            }
            item.setCode(code);
            item.setTnvedCode(code);
            item.setName(multilingualName(row, tnvedName(row, code)));
            item.setDescription(description(row));
            item.setStatus(status(row));
            item.setParentCode(normalize(firstText(row, "parentCode", "parent_code", "parent")));
            item.setSectionCode(normalize(firstText(row, "sectionCode", "section_code", "section")));
            item.setUnitCode(normalize(firstText(row, "unitCode", "unit_code", "uom", "categoryUnitName", "unitsName", "recommendedUnitsName")));
            item.setDutyRate(decimal(row, "dutyRate", "duty_rate", "customsDutyRate"));
            item.setVatRate(decimal(row, "vatRate", "vat_rate", "ndsRate", "rateVat"));
            item.setSourceSystem(source.sourceSystem());
            item.setExternalId(firstText(row, "externalId", "id", "guid"));
            item.setLastSyncedAt(now);
            tnvedCodeRepository.save(item);
        }
        ProductCodeSyncResult result = new ProductCodeSyncResult(
            "TNVED",
            source.sourceSystem(),
            source.mode(),
            source.rows().size(),
            counter.created,
            counter.updated,
            source.pagesFetched(),
            source.complete(),
            source.message(),
            now
        );
        integrationEventService.emit(source.sourceSystem() + ".TNVED_CODES_SYNCED", "TNVED_CODE", null, payload(result, request));
        return result;
    }
    private SyncSource sourceRows(ProductCodeSyncRequest request, ProductCodeDictionary dictionary, List<Map<String, Object>> mockRows) {
        String path = sourcePath(request == null ? null : request.sourcePath(), dictionary);
        if (request != null && request.records() != null && !request.records().isEmpty()) {
            return new SyncSource(asRows(objectMapper.valueToTree(request.records())), "MANUAL", sourceSystem(dictionary, path), 1, true, "Loaded from request records");
        }
        if (dictionary == ProductCodeDictionary.TNVED && customsTnvedPath(path)) {
            return fetchCustomsTnvedRows(request, path);
        }
        if (absoluteUrl(path)) {
            return fetchLiveRows(integrationSettingService.endpoint(ExternalIntegrationSystem.SOLIQ), request, path);
        }
        IntegrationSettingService.RuntimeEndpoint endpoint = integrationSettingService.endpoint(ExternalIntegrationSystem.SOLIQ);
        if (!endpoint.enabled() || !endpoint.configured() || endpoint.mockMode()) {
            String message = !endpoint.enabled()
                ? "Soliq disabled: loaded demo mock rows only"
                : !endpoint.configured()
                    ? "Soliq base URL is not configured: loaded demo mock rows only"
                    : "Soliq mock mode is enabled: loaded demo mock rows only";
            if (request != null && Boolean.TRUE.equals(request.requireLive())) {
                throw new BusinessException(message + ". Disable test mode and configure live Soliq API to load the full catalog.");
            }
            return new SyncSource(asRows(objectMapper.valueToTree(mockRows)), "MOCK", sourceSystem(dictionary, path), 1, true, message);
        }
        return fetchLiveRows(endpoint, request, path);
    }
    private SyncSource fetchCustomsTnvedRows(ProductCodeSyncRequest request, String path) {
        int pageSize = bounded(request == null ? null : request.pageSize(), 1, CUSTOMS_TNVED_MAX_PAGE_SIZE, CUSTOMS_TNVED_PAGE_SIZE);
        int page = positive(request == null ? null : request.startPage(), 0);
        int startOffset = page * pageSize;
        int maxPages = bounded(request == null ? null : request.maxPages(), 1, DEFAULT_MAX_PAGES, DEFAULT_MAX_PAGES);
        boolean fullSync = request == null || request.fullSync() == null || request.fullSync();
        List<JsonNode> result = new ArrayList<>();
        Set<String> seenRows = new LinkedHashSet<>();
        int pagesFetched = 0;
        Integer total = null;
        boolean complete = true;
        String stopReason = null;
        while (pagesFetched < maxPages) {
            JsonNode body = postCustomsTnvedJson(path, startOffset, pageSize, pagesFetched + 1);
            total = total == null ? firstIntValue(body, "iTotalRecords", "iTotalDisplayRecords", "recordsTotal", "recordsFiltered", "total") : total;
            List<JsonNode> rows = customsTnvedRows(body);
            for (JsonNode row : rows) {
                if (seenRows.add(rowKey(row))) {
                    result.add(row);
                }
            }
            pagesFetched++;
            if (rows.isEmpty() || !fullSync || (total != null && startOffset + rows.size() >= total)) {
                break;
            }
            startOffset += pageSize;
        }
        if (fullSync && pagesFetched >= maxPages && (total == null || result.size() < total)) {
            complete = false;
            stopReason = "Reached maxPages=" + maxPages + " before Customs reported the last page";
        }
        if (Boolean.TRUE.equals(request == null ? null : request.requireLive()) && result.isEmpty()) {
            throw new BusinessException("Customs TN VED catalog returned 0 rows. Check tarif.customs.uz endpoint and network access.");
        }
        if (Boolean.TRUE.equals(request == null ? null : request.requireLive()) && fullSync && !complete) {
            throw new BusinessException("Customs TN VED full sync is incomplete after " + pagesFetched + " page(s), " + result.size() + " row(s). " + firstNonBlank(stopReason, "Provider pagination stopped before the end."));
        }
        String totalText = total == null ? "unknown total" : total + " total row(s)";
        String message = !fullSync
            ? "Loaded requested live TN VED page from Customs (" + totalText + ")"
            : complete
            ? "Loaded all available live TN VED pages from Customs (" + totalText + ")"
            : firstNonBlank(stopReason, "Stopped before the end of Customs TN VED catalog; increase maxPages or check provider pagination");
        return new SyncSource(result, "LIVE", CUSTOMS_SOURCE_SYSTEM, pagesFetched, complete, message);
    }
    private SyncSource fetchLiveRows(IntegrationSettingService.RuntimeEndpoint endpoint, ProductCodeSyncRequest request, String path) {
        int pageSize = bounded(request == null ? null : request.pageSize(), 1, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE);
        int page = positive(request == null ? null : request.startPage(), 0);
        int startPage = page;
        int maxPages = bounded(request == null ? null : request.maxPages(), 1, DEFAULT_MAX_PAGES, DEFAULT_MAX_PAGES);
        boolean fullSync = request == null || request.fullSync() == null || request.fullSync();
        String pageParam = firstNonBlank(request == null ? null : request.pageParam(), "page");
        String sizeParam = firstNonBlank(request == null ? null : request.sizeParam(), "size");
        List<JsonNode> result = new ArrayList<>();
        Set<String> fetchedUris = new LinkedHashSet<>();
        Set<String> seenRows = new LinkedHashSet<>();
        String nextPath = path;
        int pagesFetched = 0;
        boolean complete = true;
        String stopReason = null;
        while (pagesFetched < maxPages) {
            String requestPath = pagePath(nextPath, page, pageSize, pageParam, sizeParam);
            String uri = uri(endpoint, requestPath);
            if (!fetchedUris.add(uri)) {
                complete = false;
                stopReason = "Soliq pagination returned the same URL twice: " + uri;
                break;
            }
            JsonNode body = getJson(endpoint, uri);
            PageResult pageResult = pageResult(body);
            List<JsonNode> uniqueRows = new ArrayList<>();
            for (JsonNode row : pageResult.rows()) {
                if (seenRows.add(rowKey(row))) {
                    uniqueRows.add(row);
                }
            }
            result.addAll(uniqueRows);
            pagesFetched++;
            if (pagesFetched > 1 && uniqueRows.isEmpty() && !pageResult.rows().isEmpty()) {
                complete = false;
                stopReason = "Soliq returned duplicate rows on the next page; check pageParam/sizeParam or provider pagination";
                break;
            }
            if (pageResult.rows().isEmpty() || !fullSync || !hasNext(body, pageResult, page, startPage, pageSize, result.size(), uniqueRows.size())) {
                break;
            }
            if (pageResult.nextPath() != null && !pageResult.nextPath().isBlank()) {
                nextPath = pageResult.nextPath();
            } else {
                page++;
            }
        }
        if (pagesFetched >= maxPages) {
            complete = false;
            stopReason = "Reached maxPages=" + maxPages + " before Soliq reported the last page";
        }
        if (fullSync && officialTasnifPath(path) && !result.isEmpty() && result.size() < pageSize && pagesFetched <= 2) {
            complete = false;
            stopReason = "Official Tasnif IKPU catalog returned only " + result.size()
                + " row(s) with pageSize=" + pageSize
                + "; the full catalog was not confirmed. Check provider availability, sourcePath and pagination.";
        }
        if (Boolean.TRUE.equals(request == null ? null : request.requireLive()) && result.isEmpty()) {
            throw new BusinessException("Soliq product-code catalog returned 0 rows. Check Tasnif/Soliq endpoint, credentials, network access, and sourcePath.");
        }
        if (Boolean.TRUE.equals(request == null ? null : request.requireLive()) && fullSync && !complete) {
            throw new BusinessException("Soliq product-code full sync is incomplete after " + pagesFetched + " page(s), " + result.size() + " row(s). " + firstNonBlank(stopReason, "Provider pagination stopped before the end."));
        }
        String message = complete
            ? "Loaded all available live pages from Soliq"
            : firstNonBlank(stopReason, "Stopped before the end of Soliq catalog; increase maxPages or check provider pagination");
        return new SyncSource(result, "LIVE", ExternalIntegrationSystem.SOLIQ.name(), pagesFetched, complete, message);
    }
    private JsonNode getJson(IntegrationSettingService.RuntimeEndpoint endpoint, String uri) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
            endpoint.headers().forEach(builder::header);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("Soliq product-code endpoint returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Soliq product-code response must be JSON");
        } catch (Exception ex) {
            throw new BusinessException("Cannot sync product codes from Soliq: " + ex.getMessage());
        }
    }
    private JsonNode postCustomsTnvedJson(String path, int startOffset, int pageSize, int echo) {
        String uri = absoluteUrl(path) ? path : "https://tarif.customs.uz" + (path.startsWith("/") ? path : "/" + path);
        String body = formBody(Map.of(
            "sEcho", String.valueOf(echo),
            "iColumns", "4",
            "iDisplayStart", String.valueOf(startOffset),
            "iDisplayLength", String.valueOf(pageSize),
            "sSearch", "",
            "bRegex", "false",
            "iSortCol_0", "0",
            "sSortDir_0", "asc",
            "iSortingCols", "1"
        ));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(90))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://tarif.customs.uz/spravochnik/viewDatatable.jsp?lang=ru_Ru")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = customsHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("Customs TN VED endpoint returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Customs TN VED response must be JSON");
        } catch (Exception ex) {
            throw new BusinessException("Cannot sync TN VED codes from Customs: " + ex.getMessage());
        }
    }
    private String formBody(Map<String, String> values) {
        List<String> pairs = new ArrayList<>();
        values.forEach((key, value) -> pairs.add(enc(key) + "=" + enc(value)));
        return String.join("&", pairs);
    }
    private static HttpClient customsHttpClient() {
        try {
            TrustManager[] trustManagers = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }
                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new SecureRandom());
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .sslContext(sslContext)
                .build();
        } catch (GeneralSecurityException ex) {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        }
    }
    private PageResult pageResult(JsonNode body) {
        return new PageResult(asRows(rows(body)), nextPath(body));
    }
    private List<JsonNode> customsTnvedRows(JsonNode body) {
        JsonNode rows = body == null ? null : body.path("aaData");
        if (rows == null || !rows.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 2) {
                continue;
            }
            String code = row.path(0).asText("").trim();
            String name = row.path(1).asText("").trim();
            if (code.isBlank() || name.isBlank()) {
                continue;
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("tnvedCode", code);
            item.put("name", name);
            item.put("nameRu", name);
            if (row.size() > 2) {
                item.put("unitCode", row.path(2).asText(""));
            }
            if (row.size() > 3) {
                item.put("additionalUnitCode", row.path(3).asText(""));
            }
            result.add(item);
        }
        return result;
    }
    private JsonNode rows(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isArray()) {
            return body;
        }
        for (String key : List.of("items", "data", "result", "content", "rows", "records", "list", "products")) {
            JsonNode value = body.path(key);
            if (value.isArray()) {
                return value;
            }
            JsonNode nestedContent = value.path("content");
            if (nestedContent.isArray()) {
                return nestedContent;
            }
            JsonNode nestedItems = value.path("items");
            if (nestedItems.isArray()) {
                return nestedItems;
            }
        }
        return objectMapper.createArrayNode();
    }
    private boolean hasNext(JsonNode body, PageResult pageResult, int page, int startPage, int pageSize, int totalLoaded, int uniqueLoadedOnPage) {
        if (pageResult.nextPath() != null && !pageResult.nextPath().isBlank()) {
            return true;
        }
        Boolean explicit = firstBoolValue(body, "hasNext", "has_next", "nextPageAvailable", "hasMore", "has_more");
        if (explicit != null) {
            return explicit;
        }
        Boolean last = firstBoolValue(body, "last");
        if (last != null) {
            return !last;
        }
        Integer totalPages = firstIntValue(body, "totalPages", "total_pages", "pageCount", "pages");
        if (totalPages != null) {
            return startPage <= 0 ? page + 1 < totalPages : page < totalPages;
        }
        Integer total = firstIntValue(body, "totalElements", "total_elements", "totalCount", "total_count", "total");
        if (total != null) {
            return totalLoaded < total;
        }
        return !pageResult.rows().isEmpty() && uniqueLoadedOnPage > 0;
    }
    private String nextPath(JsonNode body) {
        String direct = firstText(body, "next", "nextPage", "next_page", "nextUrl", "next_url");
        if (direct != null) {
            return direct;
        }
        JsonNode links = body == null ? null : body.path("links");
        return firstText(links, "next", "nextPage", "next_url");
    }
    private String rowKey(JsonNode row) {
        String code = firstText(row, "ikpuCode", "mxikCode", "mxik", "ikpu", "tnvedCode", "tnved", "tnVed", "hsCode", "hs_code", "internationalCode", "international_code", "code");
        if (code != null) {
            return normalize(code);
        }
        String id = firstText(row, "externalId", "id", "guid", "pkey");
        if (id != null) {
            return "ID:" + id;
        }
        return "ROW:" + row.toString();
    }
    private List<JsonNode> asRows(JsonNode rows) {
        if (rows == null || !rows.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        rows.forEach(result::add);
        return result;
    }
    private String firstText(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode value = row == null ? null : row.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }
    private Integer intValue(JsonNode row, String... keys) {
        String raw = firstText(row, keys);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    private Integer firstIntValue(JsonNode row, String... keys) {
        Integer direct = intValue(row, keys);
        if (direct != null) {
            return direct;
        }
        for (String container : List.of("data", "result", "page", "pagination", "meta")) {
            Integer nested = intValue(row == null ? null : row.path(container), keys);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
    private Boolean boolValue(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode value = row == null ? null : row.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            String raw = value.asText("");
            if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw)) {
                return true;
            }
            if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "no".equalsIgnoreCase(raw)) {
                return false;
            }
        }
        return null;
    }
    private Boolean firstBoolValue(JsonNode row, String... keys) {
        Boolean direct = boolValue(row, keys);
        if (direct != null) {
            return direct;
        }
        for (String container : List.of("data", "result", "page", "pagination", "meta")) {
            Boolean nested = boolValue(row == null ? null : row.path(container), keys);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
    private String status(JsonNode row) {
        String raw = firstText(row, "status", "state", "isActive");
        if (raw == null) {
            return "ACTIVE";
        }
        if ("1".equals(raw) || "true".equalsIgnoreCase(raw) || "active".equalsIgnoreCase(raw)) {
            return "ACTIVE";
        }
        return normalize(raw);
    }
    private String description(JsonNode row) {
        String ru = firstText(row, "descriptionRu", "nameRu", "ru");
        String en = firstText(row, "descriptionEn", "nameEn", "en");
        String uz = firstText(row, "descriptionUz", "nameUz", "uz");
        if (ru == null) {
            ru = joinNonBlank(" / ",
                firstText(row, "groupNameRu", "groupName"),
                firstText(row, "classNameRu", "className"),
                firstText(row, "positionNameRu", "positionName"),
                firstText(row, "subPositionNameRu", "subPositionName"),
                firstText(row, "attributeNameRu", "attributeName")
            );
        }
        if (uz == null) {
            uz = joinNonBlank(" / ",
                firstText(row, "groupNameUz"),
                firstText(row, "classNameUz"),
                firstText(row, "positionNameUz"),
                firstText(row, "subPositionNameUz"),
                firstText(row, "attributeNameUz")
            );
        }
        if (en == null) {
            en = firstText(row, "nameLat", "groupNameLat", "classNameLat", "positionNameLat", "subPositionNameLat");
        }
        if (ru == null && en == null && uz == null) {
            return firstText(row, "description");
        }
        return "ru: " + firstNonBlank(ru, "") + "\nen: " + firstNonBlank(en, "") + "\nuz: " + firstNonBlank(uz, "");
    }
    private String multilingualName(JsonNode row, String fallback) {
        return com.enterprise.mm.common.LocalizedText.ensureComplete(
            firstNonBlank(description(row), fallback)
        );
    }
    private BigDecimal decimal(JsonNode row, String... keys) {
        String raw = firstText(row, keys);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    private boolean bool(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode value = row == null ? null : row.get(key);
            if (value != null && !value.isNull()) {
                if (value.isBoolean()) {
                    return value.asBoolean();
                }
                String raw = value.asText("");
                return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
            }
        }
        return false;
    }
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private String entityName(String value, String fallback) {
        String resolved = firstNonBlank(value, fallback);
        return resolved.length() <= MAX_ENTITY_NAME_LENGTH ? resolved : resolved.substring(0, MAX_ENTITY_NAME_LENGTH);
    }
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
    private int positive(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }
    private int bounded(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
    private String pagePath(String path, int page, int pageSize, String pageParam, String sizeParam) {
        String result = firstNonBlank(path, "/");
        boolean hasPagePlaceholder = result.contains("{page}");
        boolean hasSizePlaceholder = result.contains("{size}") || result.contains("{pageSize}");
        result = result
            .replace("{page}", String.valueOf(page))
            .replace("{size}", String.valueOf(pageSize))
            .replace("{pageSize}", String.valueOf(pageSize));
        if (!hasPagePlaceholder) {
            result = upsertQuery(result, pageParam, String.valueOf(page));
        }
        if (!hasSizePlaceholder) {
            result = upsertQuery(result, sizeParam, String.valueOf(pageSize));
        }
        return result;
    }
    private String upsertQuery(String path, String key, String value) {
        String fragment = "";
        String target = path;
        int fragmentStart = target.indexOf('#');
        if (fragmentStart >= 0) {
            fragment = target.substring(fragmentStart);
            target = target.substring(0, fragmentStart);
        }
        String encodedKey = enc(key);
        String encodedValue = enc(value);
        int queryStart = target.indexOf('?');
        if (queryStart < 0) {
            return target + "?" + encodedKey + "=" + encodedValue + fragment;
        }
        String base = target.substring(0, queryStart);
        String query = target.substring(queryStart + 1);
        String[] parts = query.isBlank() ? new String[0] : query.split("&");
        List<String> updated = new ArrayList<>();
        boolean replaced = false;
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String name = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
            if (name.equals(key) || name.equals(encodedKey)) {
                updated.add(encodedKey + "=" + encodedValue);
                replaced = true;
            } else {
                updated.add(part);
            }
        }
        if (!replaced) {
            updated.add(encodedKey + "=" + encodedValue);
        }
        return base + "?" + String.join("&", updated) + fragment;
    }
    private String uri(IntegrationSettingService.RuntimeEndpoint endpoint, String path) {
        if (absoluteUrl(path)) {
            return path;
        }
        return endpoint.uri(path);
    }
    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    private String sourcePath(String requestedPath, ProductCodeDictionary dictionary) {
        String path = firstNonBlank(requestedPath, dictionary.defaultPath);
        String normalized = path.trim().toLowerCase(Locale.ROOT);
        if (dictionary.legacyPaths.contains(normalized)) {
            return dictionary.defaultPath;
        }
        return path;
    }
    private boolean absoluteUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }
    private boolean customsTnvedPath(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("/hscode-list") || normalized.endsWith("/hscode-list");
    }
    private boolean officialTasnifPath(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(TASNIF_BASE_URL.toLowerCase(Locale.ROOT));
    }
    private String sourceSystem(ProductCodeDictionary dictionary, String path) {
        return dictionary == ProductCodeDictionary.TNVED && customsTnvedPath(path)
            ? CUSTOMS_SOURCE_SYSTEM
            : ExternalIntegrationSystem.SOLIQ.name();
    }
    private String tnvedCode(JsonNode row) {
        return normalize(firstText(row, "tnvedCode", "tnved", "tnVed", "tnVedCode", "tnved_code", "hsCode", "hs_code", "internationalCode", "international_code", "commodityCode", "code"));
    }
    private String tnvedName(JsonNode row, String code) {
        return entityName(firstNonBlank(firstText(row, "tnvedName", "tnvedNameRu", "hsName", "hsNameRu", "commodityName", "commodityNameRu", "name", "nameRu", "title", "fullName", "label", "subPositionNameRu", "positionNameRu", "classNameRu", "groupNameRu"), "TN VED Item"), "TN VED Item");
    }
    private void upsertTnvedFromLinkedRow(JsonNode row, LocalDateTime now) {
        String code = tnvedCode(row);
        if (code == null) {
            return;
        }
        TnvedCode item = tnvedCodeRepository.findByTenantIdAndCompanyIdAndTnvedCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), code)
            .orElseGet(TnvedCode::new);
        item.setCode(code);
        item.setTnvedCode(code);
        item.setName(multilingualName(row, tnvedName(row, code)));
        item.setDescription(description(row));
        item.setStatus(status(row));
        item.setUnitCode(normalize(firstText(row, "unitCode", "unit_code", "uom", "categoryUnitName", "unitsName", "recommendedUnitsName")));
        item.setVatRate(decimal(row, "vatRate", "vat_rate", "ndsRate", "rateVat"));
        item.setSourceSystem(ExternalIntegrationSystem.SOLIQ.name());
        item.setExternalId(firstText(row, "tnvedExternalId", "externalId", "id", "guid"));
        item.setLastSyncedAt(now);
        tnvedCodeRepository.save(item);
    }
    private void upsertMxik(JsonNode row, String code) {
        MXIK mxik = mxikRepository.findByKod(code).orElseGet(MXIK::new);
        mxik.setKod(code);
        String synchronizedName = entityName(firstNonBlank(firstText(
            row,
            "name",
            "nameRu",
            "title",
            "productName",
            "fullName",
            "label",
            "subPositionNameRu",
            "subPositionName",
            "positionNameRu",
            "positionName",
            "classNameRu",
            "className",
            "groupNameRu",
            "groupName"
        ), "MXIK Item"), "MXIK Item");
        mxik.setName(synchronizedName);
        String synchronizedNameRu = trimToNull(firstText(row, "nameRu", "descriptionRu"));
        if (synchronizedNameRu != null) {
            mxik.setNameRu(synchronizedNameRu);
        }
        mxik.setType(firstNonBlank(trimToNull(firstText(row, "type", "classifierType")), bool(row, "service", "isService", "is_service") ? "SERVICE" : "GOODS"));
        mxik.setGroupName(trimToNull(firstText(row, "groupName", "groupCode", "group")));
        mxik.setGroupNameRu(trimToNull(firstText(row, "groupNameRu", "groupName")));
        mxik.setGroupNameCyril(trimToNull(firstText(row, "groupNameCyril", "groupNameRu")));
        mxik.setClassName(trimToNull(firstText(row, "className", "classCode", "class_code")));
        mxik.setClassNameRu(trimToNull(firstText(row, "classNameRu", "className")));
        mxik.setClassNameCyril(trimToNull(firstText(row, "classNameCyril", "classNameRu")));
        mxik.setPositionName(trimToNull(firstText(row, "positionName", "position")));
        mxik.setPositionNameRu(trimToNull(firstText(row, "positionNameRu", "positionName")));
        mxik.setPositionNameCyril(trimToNull(firstText(row, "positionNameCyril", "positionNameRu")));
        mxik.setSubPositionName(trimToNull(firstText(row, "subPositionName", "subPosition")));
        mxik.setSubPositionNameRu(trimToNull(firstText(row, "subPositionNameRu", "subPositionName")));
        mxik.setSubPositionNameCyril(trimToNull(firstText(row, "subPositionNameCyril", "subPositionNameRu")));
        mxik.setBrandName(trimToNull(firstText(row, "brandName", "brand")));
        mxik.setBrandNameRu(trimToNull(firstText(row, "brandNameRu", "brandName")));
        mxik.setBrandNameCyril(trimToNull(firstText(row, "brandNameCyril", "brandNameRu")));
        mxik.setAttributeName(trimToNull(firstText(row, "attributeName", "attribute")));
        mxik.setAttributeNameRu(trimToNull(firstText(row, "attributeNameRu", "attributeName")));
        mxik.setAttributeNameCyril(trimToNull(firstText(row, "attributeNameCyril", "attributeNameRu")));
        mxik.setBarcode(trimToNull(firstText(row, "barcode", "gtin")));
        mxikRepository.save(mxik);
    }
    private String joinNonBlank(String separator, String... parts) {
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values.isEmpty() ? null : String.join(separator, values);
    }
    private Map<String, Object> payload(ProductCodeSyncResult result, ProductCodeSyncRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dictionary", result.dictionary());
        payload.put("sourceSystem", result.sourceSystem());
        payload.put("received", result.received());
        payload.put("created", result.created());
        payload.put("updated", result.updated());
        payload.put("mode", result.mode());
        payload.put("pagesFetched", result.pagesFetched());
        payload.put("complete", result.complete());
        payload.put("message", result.message());
        payload.put("syncedAt", result.syncedAt());
        payload.put("sourcePath", request == null ? null : request.sourcePath());
        return payload;
    }
    private List<Map<String, Object>> mockIkpuRows() {
        return List.of(
            Map.of("ikpuCode", "01234501001000000", "name", "Steel sheet 2mm", "nameRu", "Стальной лист 2 мм", "nameUz", "2 mm po'lat list", "unitCode", "PCS", "groupCode", "METAL", "vatRate", "12"),
            Map.of("ikpuCode", "01234501002000000", "name", "Steel pipe DN50", "nameRu", "Труба стальная DN50", "nameUz", "DN50 po'lat quvur", "unitCode", "M", "groupCode", "METAL", "vatRate", "12"),
            Map.of("ikpuCode", "01234502001000000", "name", "Copper cable 16mm2", "nameRu", "Кабель медный 16 мм2", "nameUz", "16 mm2 mis kabel", "unitCode", "M", "groupCode", "ELECTRICAL", "vatRate", "12"),
            Map.of("ikpuCode", "01234503001000000", "name", "Diesel fuel D2", "nameRu", "Дизельное топливо D2", "nameUz", "D2 dizel yoqilg'isi", "unitCode", "L", "groupCode", "FUEL", "vatRate", "12"),
            Map.of("ikpuCode", "01234504001000000", "name", "Works and services", "nameRu", "Работы и услуги", "nameUz", "Ishlar va xizmatlar", "unitCode", "SERVICE", "groupCode", "SERVICE", "vatRate", "12", "service", true)
        );
    }
    private List<Map<String, Object>> mockTnvedRows() {
        return List.of(
            Map.of("tnvedCode", "7208390000", "name", "Flat-rolled steel products", "nameRu", "Прокат плоский из стали", "nameUz", "Po'lat yassi prokat", "sectionCode", "XV", "unitCode", "KG", "vatRate", "12"),
            Map.of("tnvedCode", "7306307200", "name", "Steel pipes", "nameRu", "Трубы стальные", "nameUz", "Po'lat quvurlar", "sectionCode", "XV", "unitCode", "M", "vatRate", "12"),
            Map.of("tnvedCode", "8544499100", "name", "Electric cables", "nameRu", "Электрические кабели", "nameUz", "Elektr kabellar", "sectionCode", "XVI", "unitCode", "M", "vatRate", "12"),
            Map.of("tnvedCode", "2710194300", "name", "Diesel fuel", "nameRu", "Дизельное топливо", "nameUz", "Dizel yoqilg'isi", "sectionCode", "V", "unitCode", "L", "vatRate", "12"),
            Map.of("tnvedCode", "8482109008", "name", "Ball bearings", "nameRu", "Подшипники шариковые", "nameUz", "Sharli podshipniklar", "sectionCode", "XVI", "unitCode", "PCS", "vatRate", "12")
        );
    }
    private static final class SyncCounter {
        private int created;

    private int updated;
    }
    private record SyncSource(List<JsonNode> rows, String mode, String sourceSystem, int pagesFetched, boolean complete, String message) {
    }
    private record PageResult(List<JsonNode> rows, String nextPath) {
    }
    private enum ProductCodeDictionary {
        IKPU(IKPU_DEFAULT_PATH, Set.of("/api/ikpu", "/api/catalog/ikpu", "api/ikpu", "api/catalog/ikpu")),
        TNVED(TNVED_DEFAULT_PATH, Set.of("/api/tnved", "/api/catalog/tnved", "api/tnved", "api/catalog/tnved"));

    private final String defaultPath;

    private final Set<String> legacyPaths;
        ProductCodeDictionary(String defaultPath, Set<String> legacyPaths) {
            this.defaultPath = defaultPath;
            this.legacyPaths = legacyPaths;
        }
    }
}


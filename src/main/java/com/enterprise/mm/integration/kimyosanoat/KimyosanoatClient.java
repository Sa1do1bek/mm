package com.enterprise.mm.integration.kimyosanoat;

import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.IntegrationSettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Read-only transport; source credentials remain on the MM server. */
@Component
public class KimyosanoatClient {
    private final IntegrationSettingService settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public KimyosanoatClient(IntegrationSettingService settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public JsonNode list(long warehouse, int page, int size) {
        return get("/mm-issue-transfers?warehouseId=" + warehouse + "&page=" + page + "&size=" + size);
    }

    public JsonNode detail(long warehouse, long transfer) {
        return get("/mm-issue-transfers/" + transfer + "?warehouseId=" + warehouse);
    }

    public JsonNode listBy1c(String sourceId, int page, int size) {
        return get("/mm-issue-transfers/by-warehouse1c?warehouse1cId=" + warehouse1cId(sourceId) + "&page=" + page + "&size=" + size);
    }

    public JsonNode detailBy1c(String sourceId, long transfer) {
        return get("/mm-issue-transfers/by-warehouse1c/" + transfer + "?warehouse1cId=" + warehouse1cId(sourceId));
    }

    public JsonNode detailBy1cId(UUID warehouse1cId, long transfer) {
        return get("/mm-issue-transfers/by-warehouse1c/" + transfer + "?warehouse1cId=" + warehouse1cId);
    }

    public List<Long> resolveWarehousesBy1cId(UUID warehouse1cId) {
        return warehouseIds(get("/mm-issue-transfers/resolve-warehouse?warehouse1cId=" + warehouse1cId));
    }

    public long resolveWarehouse(String sourceId) {
        List<Long> ids = resolveWarehouses(sourceId);
        if (ids.size() != 1) throw new BusinessException("Kimyosanoat warehouse identity is ambiguous");
        return ids.getFirst();
    }

    public List<Long> resolveWarehouses(String sourceId) {
        return warehouseIds(get("/mm-issue-transfers/resolve-warehouse?warehouse1cId=" + warehouse1cId(sourceId)));
    }

    private List<Long> warehouseIds(JsonNode result) {
        List<Long> ids = new ArrayList<>();
        JsonNode list = result.path("warehouseIds");
        if (list.isArray()) {
            for (JsonNode id : list) {
                if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0 || ids.contains(id.longValue()))
                    throw new BusinessException("Kimyosanoat returned invalid warehouse identities");
                ids.add(id.longValue());
            }
        } else {
            JsonNode id = result.path("warehouseId");
            if (id.isIntegralNumber() && id.canConvertToLong() && id.longValue() > 0) ids.add(id.longValue());
        }
        if (ids.isEmpty()) throw new BusinessException("Kimyosanoat could not identify the warehouse");
        return List.copyOf(ids);
    }

    private String warehouse1cId(String sourceId) {
        if (sourceId == null || !sourceId.matches("(?i)[0-9a-f]{32}"))
            throw new BusinessException("Invalid imported 1C warehouse identity");
        // 1C stores Data4, Data3, Data2, Data1 in its database reference bytes.
        String warehouse1cId = (sourceId.substring(24, 32) + "-" + sourceId.substring(20, 24) + "-"
            + sourceId.substring(16, 20) + "-" + sourceId.substring(0, 4) + "-" + sourceId.substring(4, 16))
            .toLowerCase(java.util.Locale.ROOT);
        return URLEncoder.encode(warehouse1cId, StandardCharsets.UTF_8);
    }

    private JsonNode get(String path) {
        var endpoint = settings.endpoint(ExternalIntegrationSystem.KIMYOSANOAT);
        if (!endpoint.enabled() || endpoint.mockMode() || blank(endpoint.username()) || blank(endpoint.password()))
            throw new BusinessException("Kimyosanoat connection is not configured");
        if (endpoint.username().contains(":")) throw new BusinessException("Invalid Kimyosanoat username");
        String base = endpoint.baseUrl() == null ? "" : endpoint.baseUrl().replaceAll("/+$", "");
        URI uri;
        try { uri = URI.create(base); } catch (IllegalArgumentException ex) { throw new BusinessException("Invalid Kimyosanoat address"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !("api-navoiy-azot.uzkimyo.uz".equalsIgnoreCase(uri.getHost())
                || "api.uzkimyo.uz".equalsIgnoreCase(uri.getHost()))
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new BusinessException("Kimyosanoat address must be its HTTPS API endpoint");
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
            (endpoint.username() + ":" + endpoint.password()).getBytes(StandardCharsets.UTF_8));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("Authorization", authorization).GET().build();
            HttpResponse<String> result = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() / 100 != 2) throw new KimyosanoatSourceHttpException(result.statusCode());
            return json.readTree(result.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Kimyosanoat connection interrupted");
        } catch (Exception ex) {
            throw new BusinessException("Kimyosanoat connection failed");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}

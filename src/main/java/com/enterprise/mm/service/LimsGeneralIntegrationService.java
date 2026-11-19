package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.entity.LaboratoryTest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LimsGeneralIntegrationService {
    public static final String CREATE_SAMPLE_PATH = "/api/v1/samples";
    private static final String PLANTS_PATH = "/api/v1/plants";
    private static final String LABS_PATH = "/api/v1/labs";
    private static final String SAMPLE_TYPES_PATH = "/api/v1/dictionaries/sample-types";
    private static final String SAMPLE_SOURCES_PATH = "/api/v1/dictionaries/sample-sources";
    private static final String PRIORITIES_PATH = "/api/v1/dictionaries/priorities";
    private final IntegrationSettingService integrationSettingService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Optional<CreatedLimsSample> createSampleIfLive(LaboratoryTest test, LocalDateTime requestedAt) {
        IntegrationSettingService.RuntimeEndpoint endpoint = integrationSettingService.endpoint(ExternalIntegrationSystem.LIMS);
        if (!live(endpoint)) {
            return Optional.empty();
        }
        LimsReferences references = references(endpoint, test);
        Map<String, Object> request = createSampleRequest(test, requestedAt, references);
        JsonNode response = postJson(endpoint, CREATE_SAMPLE_PATH, request);
        String sampleId = text(response, "id");
        String sampleNo = text(response, "sampleNo");
        if (!hasText(sampleNo)) {
            throw new BusinessException("LIMS sample response does not contain sampleNo");
        }
        return Optional.of(new CreatedLimsSample(sampleId, sampleNo, request, response));
    }
    private LimsReferences references(IntegrationSettingService.RuntimeEndpoint endpoint, LaboratoryTest test) {
        LimsReference plant = firstReference(endpoint, PLANTS_PATH, "plant", List.of());
        String plantCode = plant.code();
        String plantQuery = hasText(plantCode) ? "?plantCode=" + enc(plantCode) : "";
        LimsReference lab = firstReference(endpoint, LABS_PATH + plantQuery, "lab", List.of("QUALITY", "CHEMICAL", "MAIN"));
        LimsReference sampleType = firstReference(
            endpoint,
            SAMPLE_TYPES_PATH + plantQuery,
            "sample type",
            List.of(test.getTestType(), "MATERIAL", "RAW_MATERIAL", "INCOMING")
        );
        LimsReference sampleSource = firstReference(
            endpoint,
            SAMPLE_SOURCES_PATH + plantQuery,
            "sample source",
            List.of("MM", "ERP", "INCOMING", "PRODUCTION")
        );
        LimsReference priority = firstReference(endpoint, PRIORITIES_PATH + plantQuery, "priority", List.of("NORMAL", "MEDIUM", "STANDARD"));
        return new LimsReferences(plant, lab, sampleType, sampleSource, priority);
    }
    Map<String, Object> createSampleRequest(LaboratoryTest test, LocalDateTime requestedAt, LimsReferences references) {
        String requestedAtText = offsetText(requestedAt);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("plantId", references.plant().id());
        request.put("labId", references.lab().id());
        request.put("sampleTypeId", references.sampleType().id());
        request.put("sampleSourceId", references.sampleSource().id());
        request.put("priorityId", references.priority().id());
        request.put("takenAt", requestedAtText);
        request.put("receivedAt", requestedAtText);
        request.put("dueAt", offsetText(requestedAt.plusDays(1)));
        request.put("description", description(test));
        request.put("externalRef", "MM-LAB-" + test.getId());
        request.put("controlType", "INCOMING");
        return request;
    }
    private LimsReference firstReference(IntegrationSettingService.RuntimeEndpoint endpoint, String path, String name, List<String> preferredCodes) {
        JsonNode body = getJson(endpoint, path);
        JsonNode rows = rows(body);
        if (!rows.isArray() || rows.isEmpty()) {
            throw new BusinessException("LIMS " + name + " catalog is empty");
        }
        JsonNode selected = null;
        for (JsonNode row : rows) {
            if (!active(row)) {
                continue;
            }
            if (selected == null) {
                selected = row;
            }
            if (matchesPreference(row, preferredCodes)) {
                selected = row;
                break;
            }
        }
        if (selected == null) {
            throw new BusinessException("LIMS " + name + " catalog has no active records");
        }
        String id = text(selected, "id");
        if (!hasText(id)) {
            throw new BusinessException("LIMS " + name + " record does not contain id");
        }
        return new LimsReference(id, text(selected, "code"), text(selected, "name"));
    }
    private JsonNode getJson(IntegrationSettingService.RuntimeEndpoint endpoint, String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.uri(path)))
            .timeout(Duration.ofSeconds(12))
            .header("Accept", "application/json")
            .GET();
        headers(endpoint).forEach(builder::header);
        return send(builder.build(), "LIMS " + path);
    }
    private JsonNode postJson(IntegrationSettingService.RuntimeEndpoint endpoint, String path, Map<String, Object> payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.uri(path)))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            headers(endpoint).forEach(builder::header);
            return send(builder.build(), "LIMS " + path);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Cannot serialize LIMS sample request");
        }
    }
    private JsonNode send(HttpRequest request, String context) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(context + " returned HTTP " + response.statusCode() + responseBodySuffix(response.body()));
            }
            String body = response.body();
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (BusinessException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(context + " response must be JSON");
        } catch (Exception ex) {
            throw new BusinessException("Cannot call " + context + ": " + ex.getMessage());
        }
    }
    private Map<String, String> headers(IntegrationSettingService.RuntimeEndpoint endpoint) {
        Map<String, String> headers = new LinkedHashMap<>(endpoint.headers());
        headers.putIfAbsent("X-Source-System", "lims-web");
        return headers;
    }
    private boolean live(IntegrationSettingService.RuntimeEndpoint endpoint) {
        return endpoint.enabled()
            && !endpoint.mockMode()
            && endpoint.baseUrl() != null
            && !endpoint.baseUrl().isBlank();
    }
    private JsonNode rows(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (body.isArray()) {
            return body;
        }
        for (String field : List.of("items", "data", "rows", "content")) {
            JsonNode value = body.path(field);
            if (value.isArray()) {
                return value;
            }
        }
        return objectMapper.createArrayNode();
    }
    private boolean active(JsonNode row) {
        JsonNode active = row.path("isActive");
        return active.isMissingNode() || active.isNull() || active.asBoolean(true);
    }
    private boolean matchesPreference(JsonNode row, List<String> preferredCodes) {
        if (preferredCodes == null || preferredCodes.isEmpty()) {
            return false;
        }
        String code = normalize(text(row, "code"));
        String name = normalize(text(row, "name"));
        return preferredCodes.stream()
            .filter(this::hasText)
            .map(this::normalize)
            .anyMatch(preferred -> preferred.equals(code) || preferred.equals(name));
    }
    private String description(LaboratoryTest test) {
        StringBuilder builder = new StringBuilder("MM laboratory test ").append(test.getId());
        if (hasText(test.getSampleNumber())) {
            builder.append("; sample ").append(test.getSampleNumber());
        }
        if (hasText(test.getTestType())) {
            builder.append("; type ").append(test.getTestType());
        }
        if (hasText(test.getTestMethod())) {
            builder.append("; method ").append(test.getTestMethod());
        }
        if (hasText(test.getSpecification())) {
            builder.append("; specification ").append(test.getSpecification());
        }
        String value = builder.toString();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
    private String offsetText(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
    }
    private String responseBodySuffix(String body) {
        if (!hasText(body)) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        int maxLength = 500;
        return ": " + (compact.length() > maxLength ? compact.substring(0, maxLength) : compact);
    }
    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
    }
    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
    record CreatedLimsSample(String sampleId, String sampleNo, Map<String, Object> request, JsonNode response) {
    }
    record LimsReferences(LimsReference plant, LimsReference lab, LimsReference sampleType, LimsReference sampleSource, LimsReference priority) {
    }
    record LimsReference(String id, String code, String name) {
    }
}


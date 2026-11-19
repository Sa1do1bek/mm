package com.enterprise.mm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/** Resource work uses explicit scope from the durable event, never a scheduler thread's context. */
@Service
public class ResourceKnowledgeClient {
    private final RestTemplate http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String apiKey;

    public record IndexMetadata(UUID jobId, String tenantId, UUID companyId, UUID resourceId,
                                UUID versionId, String title, String versionLabel, String fileName,
                                String contentType, String sha256, String language,
                                long knowledgeRevision, int indexGeneration) {}
    public record Visibility(UUID resourceId, long knowledgeRevision, String state) {}

    public ResourceKnowledgeClient(RestTemplateBuilder builder, ObjectMapper json,
                                   @Value("${mm.ai.base-url:}") String baseUrl,
                                   @Value("${mm.ai.api-key:}") String apiKey) {
        this.http = builder.setConnectTimeout(Duration.ofSeconds(5)).setReadTimeout(Duration.ofSeconds(20)).build();
        this.json = json;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isConfigured() { return !baseUrl.isBlank() && !apiKey.isBlank(); }

    public JsonNode index(IndexMetadata metadata, byte[] bytes) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("metadata", json.valueToTree(metadata).toString());
        var fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(metadata.contentType()));
        parts.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return metadata.fileName(); }
        }, fileHeaders));
        var headers = headers(metadata.tenantId(), metadata.companyId());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return exchange("/v1/resource-knowledge/versions/index", HttpMethod.POST, parts, headers);
    }

    public JsonNode status(String tenant, UUID company, UUID job) {
        return exchange("/v1/resource-knowledge/jobs/" + job, HttpMethod.GET, null, headers(tenant, company));
    }

    public JsonNode visibility(String tenant, UUID company, UUID version, Visibility body) {
        var headers = headers(tenant, company);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return exchange("/v1/resource-knowledge/versions/" + version + "/visibility", HttpMethod.PUT, body, headers);
    }

    private HttpHeaders headers(String tenant, UUID company) {
        var headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.set("X-Tenant-Id", tenant);
        headers.set("X-Company-Id", company.toString());
        return headers;
    }

    private JsonNode exchange(String path, HttpMethod method, Object body, HttpHeaders headers) {
        if (!isConfigured()) throw new MmAiServiceException(503, "RESOURCE_AI_NOT_CONFIGURED", "Resource AI is not configured", null);
        try {
            return http.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
        } catch (RestClientResponseException ex) {
            String code = "RESOURCE_AI_HTTP_" + ex.getStatusCode().value();
            try {
                JsonNode error = json.readTree(ex.getResponseBodyAsString()).path("error");
                String reported = error.path("errorCode").asText("");
                if (reported.matches("[A-Z][A-Z0-9_]{0,99}")) code = reported;
            } catch (Exception ignored) { /* Only a bounded public error code crosses this boundary. */ }
            throw new MmAiServiceException(ex.getStatusCode().value(), code, "Resource AI request failed", null);
        } catch (ResourceAccessException ex) {
            throw new MmAiServiceException(503, "RESOURCE_AI_UNAVAILABLE", "Resource AI is unavailable", null);
        }
    }
}

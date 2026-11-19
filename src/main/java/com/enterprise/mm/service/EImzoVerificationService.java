package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ExternalIntegrationDtos.EImzoDocumentSignatureRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileSignatureRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.entity.MobileWarehouseOperation;
import com.enterprise.mm.config.IntegrationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EImzoVerificationService {
    private final IntegrationProperties integrationProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public void verify(MobileWarehouseOperation operation, MobileSignatureRequest request) {
        if (request == null || blank(request.signaturePayload())) {
            throw new BusinessException("Signature payload is required");
        }
        IntegrationProperties.SystemEndpoint endpoint = integrationProperties.endpoint(ExternalIntegrationSystem.E_IMZO);
        if (!endpoint.isEnabled()) {
            throw new BusinessException("E-IMZO integration is disabled");
        }
        if (endpoint.isMockMode() || blank(endpoint.getBaseUrl())) {
            if (blank(request.pkcs7()) && request.signaturePayload().length() < 8) {
                throw new BusinessException("E-IMZO mock signature payload is too short");
            }
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("operationId", operation.getId());
            payload.put("operationType", operation.getOperationType());
            payload.put("signaturePayload", request.signaturePayload());
            payload.put("pkcs7", request.pkcs7());
            payload.put("certificateSerial", request.certificateSerial());
            payload.put("signerTin", request.signerTin());
            HttpRequest httpRequest = HttpRequest.newBuilder(uri(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("E-IMZO verify endpoint returned HTTP " + response.statusCode());
            }
            JsonNode body = response.body() == null || response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            if (body.has("success") && !body.path("success").asBoolean()) {
                throw new BusinessException("E-IMZO signature verification failed");
            }
            if (body.has("verified") && !body.path("verified").asBoolean()) {
                throw new BusinessException("E-IMZO signature is not verified");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("E-IMZO verification failed: " + ex.getMessage());
        }
    }
    public void verifyDocument(String entityType, UUID entityId, EImzoDocumentSignatureRequest request) {
        if (request == null || blank(request.signaturePayload())) {
            throw new BusinessException("Signature payload is required");
        }
        IntegrationProperties.SystemEndpoint endpoint = integrationProperties.endpoint(ExternalIntegrationSystem.E_IMZO);
        if (!endpoint.isEnabled()) {
            throw new BusinessException("E-IMZO integration is disabled");
        }
        if (endpoint.isMockMode() || blank(endpoint.getBaseUrl())) {
            if (blank(request.pkcs7()) && request.signaturePayload().length() < 8) {
                throw new BusinessException("E-IMZO mock signature payload is too short");
            }
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("entityType", entityType);
            payload.put("entityId", entityId);
            payload.put("signaturePayload", request.signaturePayload());
            payload.put("pkcs7", request.pkcs7());
            payload.put("certificateSerial", request.certificateSerial());
            payload.put("signerTin", request.signerTin());
            payload.put("payload", request.payload());
            HttpRequest httpRequest = HttpRequest.newBuilder(uri(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("E-IMZO verify endpoint returned HTTP " + response.statusCode());
            }
            JsonNode body = response.body() == null || response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            if (body.has("success") && !body.path("success").asBoolean()) {
                throw new BusinessException("E-IMZO signature verification failed");
            }
            if (body.has("verified") && !body.path("verified").asBoolean()) {
                throw new BusinessException("E-IMZO signature is not verified");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("E-IMZO verification failed: " + ex.getMessage());
        }
    }
    private URI uri(IntegrationProperties.SystemEndpoint endpoint) {
        String base = endpoint.getBaseUrl().endsWith("/") ? endpoint.getBaseUrl().substring(0, endpoint.getBaseUrl().length() - 1) : endpoint.getBaseUrl();
        String path = endpoint.getEventsPath() == null || endpoint.getEventsPath().isBlank() ? "/api/signature/verify" : endpoint.getEventsPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return URI.create(base + path);
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}


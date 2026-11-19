package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdempotencyService {
    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    /** Use for new commands that must not bypass idempotency when the caller omits the key. */
    public <T> T executeRequired(
        String operation,
        UUID resourceId,
        String idempotencyKey,
        Object request,
        Class<T> responseType,
        Supplier<T> action
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required");
        }
        return execute(operation, resourceId, idempotencyKey, request, responseType, action);
    }

    public <T> T execute(
        String operation,
        UUID resourceId,
        String idempotencyKey,
        Object request,
        Class<T> responseType,
        Supplier<T> action
    ) {
        return execute(operation, resourceId, idempotencyKey, request, responseType, null, action);
    }

    public <T> T execute(
        String operation,
        UUID resourceId,
        String idempotencyKey,
        Object request,
        Class<T> responseType,
        Runnable replayGuard,
        Supplier<T> action
    ) {
        return executeInternal(
            operation,
            resourceId,
            idempotencyKey,
            request,
            responseType,
            replayGuard,
            action,
            new String[0]
        );
    }

    public <T> T executeRedacted(
        String operation,
        UUID resourceId,
        String idempotencyKey,
        Object request,
        Class<T> responseType,
        Supplier<T> action,
        String... replayRedactedFields
    ) {
        return executeInternal(
            operation,
            resourceId,
            idempotencyKey,
            request,
            responseType,
            null,
            action,
            replayRedactedFields
        );
    }

    private <T> T executeInternal(
        String operation,
        UUID resourceId,
        String idempotencyKey,
        Object request,
        Class<T> responseType,
        Runnable replayGuard,
        Supplier<T> action,
        String... replayRedactedFields
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        if (replayGuard != null) {
            replayGuard.run();
        }
        UUID targetResourceId = resourceId == null ? new UUID(0L, 0L) : resourceId;
        String normalizedKey = idempotencyKey.trim();
        String requestHash = hash(request);
        var existing = repository.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            operation,
            targetResourceId,
            normalizedKey
        );
        if (existing.isPresent()) {
            if (!Objects.equals(existing.get().getRequestHash(), requestHash)) {
                throw new ConflictException("Idempotency-Key was already used with a different request payload");
            }
            return read(existing.get().getResponseJson(), responseType);
        }

        T response = action.get();
        IdempotencyRecord record = new IdempotencyRecord();
        record.setCode("IDEMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        record.setName(operation + " idempotency record");
        record.setStatus("COMPLETED");
        record.setOperation(operation);
        record.setResourceId(targetResourceId);
        record.setIdempotencyKey(normalizedKey);
        record.setRequestHash(requestHash);
        record.setResponseJson(write(response, replayRedactedFields));
        repository.save(record);
        return response;
    }

    private String hash(Object request) {
        try {
            byte[] bytes = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new BusinessException("Cannot calculate idempotency request hash");
        }
    }

    private String write(Object response, String... redactedFields) {
        try {
            if (redactedFields == null || redactedFields.length == 0) {
                return objectMapper.writeValueAsString(response);
            }
            var tree = objectMapper.valueToTree(response);
            if (tree instanceof ObjectNode objectNode) {
                for (String field : redactedFields) {
                    if (field != null && !field.isBlank()) {
                        objectNode.remove(field);
                    }
                }
            }
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException("Cannot persist idempotent response");
        }
    }

    private <T> T read(String responseJson, Class<T> responseType) {
        try {
            return objectMapper.readValue(responseJson, responseType);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("Cannot restore idempotent response");
        }
    }
}

package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AssetIntegrationEventPublisher {
    private static final String AGGREGATE_TYPE = "FIXED_ASSET";
    private static final String MATERIAL_TYPE = "FIXED_ASSET";
    private static final String SCHEMA_VERSION = "1.0";

    private final IntegrationEventService integrationEventService;

    public AssetIntegrationEventPublisher(IntegrationEventService integrationEventService) {
        this.integrationEventService = integrationEventService;
    }

    public void publish(
        FixedAsset asset,
        String eventType,
        FixedAssetLifecycleStatus beforeLifecycle,
        FixedAssetCustodyKind beforeCustody,
        String sourceDocumentType,
        UUID sourceDocumentId,
        LocalDate effectiveDate,
        String actor,
        String commandIdempotencyKey
    ) {
        if (asset == null || asset.getId() == null || eventType == null || eventType.isBlank()) {
            return;
        }
        String sourceType = blankToDefault(sourceDocumentType, AGGREGATE_TYPE);
        UUID sourceId = sourceDocumentId == null ? asset.getId() : sourceDocumentId;
        integrationEventService.emitIdempotentInCurrentTransaction(
            eventType,
            AGGREGATE_TYPE,
            asset.getId(),
            payload(asset, eventType, beforeLifecycle, beforeCustody, sourceType, sourceId, effectiveDate, actor),
            idempotencyKey(asset, eventType, sourceType, sourceId, commandIdempotencyKey)
        );
    }

    private Map<String, ?> payload(
        FixedAsset asset,
        String eventType,
        FixedAssetLifecycleStatus beforeLifecycle,
        FixedAssetCustodyKind beforeCustody,
        String sourceDocumentType,
        UUID sourceDocumentId,
        LocalDate effectiveDate,
        String actor
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("eventType", eventType);
        payload.put("tenantId", TenantContext.currentTenant());
        payload.put("companyId", asset.getCompanyId() == null ? CompanyContext.currentCompany().toString() : asset.getCompanyId().toString());
        payload.put("aggregateType", AGGREGATE_TYPE);
        payload.put("aggregateId", asset.getId().toString());
        payload.put("aggregateVersion", asset.getVersion());
        payload.put("sourceDocumentType", sourceDocumentType);
        payload.put("sourceDocumentId", sourceDocumentId.toString());
        payload.put("materialType", MATERIAL_TYPE);
        payload.put("materialId", asset.getMaterialId() == null ? null : asset.getMaterialId().toString());
        payload.put("lifecycle", asset.getLifecycleStatus() == null ? null : asset.getLifecycleStatus().name());
        payload.put("custody", asset.getCustodyKind() == null ? null : asset.getCustodyKind().name());
        payload.put("businessEffectiveDate", effectiveDate == null ? LocalDate.now().toString() : effectiveDate.toString());
        payload.put("recordedAt", LocalDateTime.now().toString());
        payload.put("actor", blankToDefault(actor, "system"));
        payload.put("before", state(beforeLifecycle, beforeCustody));
        payload.put("after", state(asset.getLifecycleStatus(), asset.getCustodyKind()));
        return payload;
    }

    private Map<String, String> state(FixedAssetLifecycleStatus lifecycle, FixedAssetCustodyKind custody) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("lifecycle", lifecycle == null ? null : lifecycle.name());
        state.put("custody", custody == null ? null : custody.name());
        return state;
    }

    private String idempotencyKey(
        FixedAsset asset,
        String eventType,
        String sourceDocumentType,
        UUID sourceDocumentId,
        String commandIdempotencyKey
    ) {
        String seed = commandIdempotencyKey == null || commandIdempotencyKey.isBlank()
            ? String.valueOf(asset.getVersion())
            : commandIdempotencyKey.trim();
        String raw = AGGREGATE_TYPE + ":" + asset.getId() + ":" + eventType + ":" + sourceDocumentType + ":" + sourceDocumentId + ":" + seed;
        return "ASSET:" + sha256(raw);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssetIntegrationEventPublisherTest {
    @Mock private IntegrationEventService integrationEventService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private AssetIntegrationEventPublisher publisher;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-integration-event-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        publisher = new AssetIntegrationEventPublisher(integrationEventService);
        when(integrationEventService.emitIdempotentInCurrentTransaction(
            any(), any(), any(), any(Map.class), any()
        )).thenReturn(new IntegrationEvent());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void fixedAssetEventsUseDurableOutboxPayloadAndDeterministicIdempotencyKey() {
        FixedAsset asset = asset();
        UUID sourceDocumentId = UUID.randomUUID();

        publisher.publish(
            asset,
            "ASSET_COMMISSIONED",
            FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING,
            FixedAssetCustodyKind.DEPARTMENT,
            "FIXED_ASSET",
            sourceDocumentId,
            LocalDate.of(2026, 9, 10),
            "asset-user",
            "command-key-1"
        );

        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(integrationEventService).emitIdempotentInCurrentTransaction(
            eq("ASSET_COMMISSIONED"),
            eq("FIXED_ASSET"),
            eq(asset.getId()),
            payload.capture(),
            eq(assetEventKey(asset, "ASSET_COMMISSIONED", "FIXED_ASSET", sourceDocumentId, "command-key-1"))
        );
        Map<String, Object> capturedPayload = Map.copyOf(payload.getValue());
        assertThat(capturedPayload)
            .containsEntry("schemaVersion", "1.0")
            .containsEntry("tenantId", TenantContext.currentTenant())
            .containsEntry("companyId", companyId.toString())
            .containsEntry("aggregateType", "FIXED_ASSET")
            .containsEntry("aggregateId", asset.getId().toString())
            .containsEntry("aggregateVersion", 7L)
            .containsEntry("sourceDocumentType", "FIXED_ASSET")
            .containsEntry("sourceDocumentId", sourceDocumentId.toString())
            .containsEntry("materialType", "FIXED_ASSET")
            .containsEntry("materialId", asset.getMaterialId().toString())
            .containsEntry("businessEffectiveDate", "2026-09-10")
            .containsEntry("actor", "asset-user");
        assertThat(capturedPayload.get("before")).isEqualTo(Map.of(
            "lifecycle", "ISSUED_PENDING_COMMISSIONING",
            "custody", "DEPARTMENT"
        ));
        assertThat(capturedPayload.get("after")).isEqualTo(Map.of(
            "lifecycle", "IN_USE",
            "custody", "DEPARTMENT"
        ));
    }

    @Test
    void retryWithoutCommandKeyUsesAggregateVersionForDeduplication() {
        FixedAsset asset = asset();
        UUID sourceDocumentId = UUID.randomUUID();

        publisher.publish(
            asset,
            "ASSET_INVENTORY_NUMBER_ASSIGNED",
            asset.getLifecycleStatus(),
            asset.getCustodyKind(),
            "FIXED_ASSET",
            sourceDocumentId,
            LocalDate.of(2026, 9, 10),
            "asset-user",
            null
        );

        verify(integrationEventService).emitIdempotentInCurrentTransaction(
            eq("ASSET_INVENTORY_NUMBER_ASSIGNED"),
            eq("FIXED_ASSET"),
            eq(asset.getId()),
            any(Map.class),
            eq(assetEventKey(asset, "ASSET_INVENTORY_NUMBER_ASSIGNED", "FIXED_ASSET", sourceDocumentId, "7"))
        );
    }

    @Test
    void payloadSerializesWithExistingIntegrationObjectMapperShape() throws Exception {
        FixedAsset asset = asset();
        UUID sourceDocumentId = UUID.randomUUID();
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);

        publisher.publish(
            asset,
            "ASSET_RETURNED_TO_WAREHOUSE",
            FixedAssetLifecycleStatus.IN_USE,
            FixedAssetCustodyKind.DEPARTMENT,
            "ASSET_HANDOVER",
            sourceDocumentId,
            LocalDate.of(2026, 9, 10),
            "asset-user",
            "return-key"
        );

        verify(integrationEventService).emitIdempotentInCurrentTransaction(
            eq("ASSET_RETURNED_TO_WAREHOUSE"), eq("FIXED_ASSET"), eq(asset.getId()), payload.capture(), any());
        String json = objectMapper.writeValueAsString(payload.getValue());

        assertThat(json).contains("\"schemaVersion\":\"1.0\"");
        assertThat(json).contains("\"materialType\":\"FIXED_ASSET\"");
        assertThat(json).doesNotContain("StockBalance");
        assertThat(json).doesNotContain("GoodsReceipt");
    }

    private FixedAsset asset() {
        FixedAsset asset = new FixedAsset();
        asset.setId(UUID.randomUUID());
        asset.setTenantId(TenantContext.currentTenant());
        asset.setCompanyId(companyId);
        asset.setCode("FA-1");
        asset.setName("Laptop");
        asset.setVersion(7L);
        asset.setMaterialId(UUID.randomUUID());
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_USE);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);
        return asset;
    }

    private String assetEventKey(
        FixedAsset asset,
        String eventType,
        String sourceDocumentType,
        UUID sourceDocumentId,
        String seed
    ) {
        String raw = "FIXED_ASSET:" + asset.getId() + ":" + eventType + ":" + sourceDocumentType + ":" + sourceDocumentId + ":" + seed;
        return "ASSET:" + sha256(raw);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

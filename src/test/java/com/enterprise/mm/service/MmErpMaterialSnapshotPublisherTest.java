package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.repository.MaterialRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
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
class MmErpMaterialSnapshotPublisherTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000008888");

    @Mock private MaterialRepository materials;
    @Mock private MaterialWmsIntegrationPublisher materialPublisher;
    @Mock private IntegrationEventService integrationEvents;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-mm-snapshot");
        CompanyContext.setCompany(COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void queuesCompleteMaterialSnapshotAsVerifiedChunksForErp() {
        Material first = material("MAT-1", 4L);
        Material second = material("MAT-2", 7L);
        when(materials.findByTenantIdAndCompanyIdOrderByIdAsc("tenant-mm-snapshot", COMPANY_ID))
            .thenReturn(List.of(first, second));
        when(materialPublisher.materialData(first)).thenReturn(data(first));
        when(materialPublisher.materialData(second)).thenReturn(data(second));

        MmErpMaterialSnapshotPublisher service = new MmErpMaterialSnapshotPublisher(
            materials, materialPublisher, integrationEvents, objectMapper);

        var result = service.queueForErp(1);

        assertThat(result.recordCount()).isEqualTo(2);
        assertThat(result.chunkCount()).isEqualTo(2);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(integrationEvents, times(2)).emitInCurrentTransaction(
            eq("mm.material.changed.v1"), eq("ERP"), eq(result.snapshotId()), payloadCaptor.capture());

        List<JsonNode> envelopes = payloadCaptor.getAllValues().stream()
            .map(payload -> (JsonNode) payload.get("raw"))
            .toList();
        assertThat(envelopes).allSatisfy(envelope -> {
            assertThat(envelope.path("kind").asText()).isEqualTo("SNAPSHOT_CHUNK");
            assertThat(envelope.path("source").asText()).isEqualTo("mm");
            assertThat(envelope.path("target").asText()).isEqualTo("erp");
            assertThat(envelope.path("data").path("domain").asText()).isEqualTo("MM_MATERIAL");
            assertThat(envelope.path("data").path("recordCount").asInt()).isEqualTo(1);
            assertThat(sha256(envelope.path("data").path("records").toString()))
                .isEqualTo(envelope.path("data").path("chunkChecksum").asText());
        });
        assertThat(envelopes.get(0).path("data").path("snapshotChecksum").asText())
            .isEqualTo(envelopes.get(1).path("data").path("snapshotChecksum").asText());
        assertThat(envelopes.stream().map(node -> node.path("data").path("records").get(0).path("data").path("canonicalId").asText()))
            .containsExactly(first.getId().toString(), second.getId().toString());
    }

    @Test
    void queuesVerifiedSnapshotChunksForWmsWithoutChangingRecordContract() {
        Material material = material("MAT-WMS", 9L);
        when(materials.findByTenantIdAndCompanyIdOrderByIdAsc("tenant-mm-snapshot", COMPANY_ID))
            .thenReturn(List.of(material));
        when(materialPublisher.materialData(material)).thenReturn(data(material));
        MmErpMaterialSnapshotPublisher service = new MmErpMaterialSnapshotPublisher(
            materials, materialPublisher, integrationEvents, objectMapper);

        var result = service.queueForWms(100);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(integrationEvents).emitInCurrentTransaction(
            eq("mm.material.changed.v1"), eq("WMS"), eq(result.snapshotId()), payloadCaptor.capture());
        JsonNode envelope = (JsonNode) payloadCaptor.getValue().get("raw");
        JsonNode record = envelope.at("/data/records/0");
        assertThat(envelope.path("target").asText()).isEqualTo("wms");
        assertThat(envelope.path("kind").asText()).isEqualTo("SNAPSHOT_CHUNK");
        assertThat(envelope.path("data").path("domain").asText()).isEqualTo("MM_MATERIAL");
        assertThat(sha256(envelope.path("data").path("records").toString()))
            .isEqualTo(envelope.path("data").path("chunkChecksum").asText());
        assertThat(record.path("aggregateId").asText()).isEqualTo(material.getId().toString());
        assertThat(record.path("aggregateVersion").asLong()).isEqualTo(9L);
    }

    private Material material(String code, long version) {
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setCode(code);
        material.setName(code + " name");
        material.setStatus("ACTIVE");
        material.setVersion(version);
        return material;
    }

    private Map<String, Object> data(Material material) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("canonicalId", material.getId());
        data.put("sku", material.getCode());
        data.put("name", material.getName());
        data.put("unit", "kg");
        data.put("qualityControlRequired", false);
        data.put("active", true);
        data.put("sourceRevision", material.getVersion());
        return data;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

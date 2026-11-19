package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.repository.MaterialRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queues a complete material-master snapshot for ERP through MM's transactional integration outbox. */
@Service
@RequiredArgsConstructor
public class MmErpMaterialSnapshotPublisher {

    private static final String EVENT_TYPE = "mm.material.changed.v1";
    private final MaterialRepository materials;
    private final MaterialWmsIntegrationPublisher materialPublisher;
    private final IntegrationEventService integrationEvents;
    private final ObjectMapper objectMapper;



    @Transactional
    public SnapshotQueueResult queueForErp(Integer requestedChunkSize) {
        return queueForTarget(requestedChunkSize, "ERP");
    }

    /** Queues the same verified material-master snapshot contract for the WMS consumer. */
    @Transactional
    public SnapshotQueueResult queueForWms(Integer requestedChunkSize) {
        return queueForTarget(requestedChunkSize, "WMS");
    }

    private SnapshotQueueResult queueForTarget(Integer requestedChunkSize, String targetSystem) {
        int chunkSize = requestedChunkSize == null ? 250 : requestedChunkSize;
        if (chunkSize < 1 || chunkSize > 500) {
            throw new IllegalArgumentException("chunkSize must be between 1 and 500");
        }
        UUID snapshotId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        long watermark = createdAt.toEpochMilli();
        ArrayNode records = objectMapper.createArrayNode();
        for (Material material : materials.findByTenantIdAndCompanyIdOrderByIdAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany())) {
            records.add(record(material));
        }
        List<ArrayNode> chunks = split(records, chunkSize);
        List<String> checksums = chunks.stream().map(chunk -> sha256(chunk.toString())).toList();
        String snapshotChecksum = snapshotChecksum(chunks, checksums);
        for (int index = 0; index < chunks.size(); index++) {
            queueChunk(snapshotId, createdAt, watermark, index, chunks, checksums, snapshotChecksum, targetSystem);
        }
        return new SnapshotQueueResult(snapshotId, records.size(), chunks.size(), watermark);
    }

    private ObjectNode record(Material material) {
        if (material.getId() == null) {
            throw new IllegalStateException("MM material snapshot requires a canonical material ID");
        }
        long revision = material.getVersion() == null ? 0L : material.getVersion();
        ObjectNode record = objectMapper.createObjectNode();
        record.put("type", EVENT_TYPE);
        record.put("schemaVersion", "1.0");
        record.put("aggregateType", "MATERIAL");
        record.put("aggregateId", material.getId().toString());
        record.put("aggregateVersion", revision);
        record.set("data", objectMapper.valueToTree(materialPublisher.materialData(material)));
        return record;
    }

    private void queueChunk(
        UUID snapshotId,
        Instant createdAt,
        long watermark,
        int index,
        List<ArrayNode> chunks,
        List<String> checksums,
        String snapshotChecksum,
        String targetSystem
    ) {
        ArrayNode records = chunks.get(index);
        ObjectNode data = objectMapper.createObjectNode();
        data.put("snapshotId", snapshotId.toString());
        data.put("domain", "MM_MATERIAL");
        data.put("watermark", watermark);
        data.put("chunkIndex", index);
        data.put("chunkCount", chunks.size());
        data.put("recordCount", records.size());
        data.put("chunkChecksum", checksums.get(index));
        data.put("snapshotChecksum", snapshotChecksum);
        data.set("records", records);
        String key = "mm:material-snapshot:" + snapshotId + ':' + index;
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("id", UUID.nameUUIDFromBytes(("event:" + key).getBytes(StandardCharsets.UTF_8)).toString());
        envelope.put("source", "mm");
        envelope.put("target", targetSystem.toLowerCase(java.util.Locale.ROOT));
        envelope.put("type", EVENT_TYPE);
        envelope.put("subject", "snapshot/" + snapshotId);
        envelope.put("time", createdAt.toString());
        envelope.put("kind", "SNAPSHOT_CHUNK");
        envelope.put("schemaVersion", "1.0");
        envelope.put("aggregateType", "SNAPSHOT");
        envelope.put("aggregateId", snapshotId.toString());
        envelope.put("aggregateVersion", watermark);
        envelope.put("idempotencyKey", key);
        envelope.set("data", data);
        integrationEvents.emitInCurrentTransaction(EVENT_TYPE, targetSystem, snapshotId, Map.of(
            "targetPath", "/api/integration/envelopes",
            "raw", envelope
        ));
    }

    private List<ArrayNode> split(ArrayNode records, int chunkSize) {
        List<ArrayNode> result = new ArrayList<>();
        if (records.isEmpty()) {
            result.add(objectMapper.createArrayNode());
            return result;
        }
        for (int start = 0; start < records.size(); start += chunkSize) {
            ArrayNode chunk = objectMapper.createArrayNode();
            for (int index = start; index < Math.min(records.size(), start + chunkSize); index++) {
                chunk.add(records.get(index));
            }
            result.add(chunk);
        }
        return result;
    }

    private String snapshotChecksum(List<ArrayNode> chunks, List<String> checksums) {
        StringBuilder manifest = new StringBuilder();
        for (int index = 0; index < chunks.size(); index++) {
            manifest.append(index).append('|').append(chunks.get(index).size()).append('|').append(checksums.get(index)).append('\n');
        }
        return sha256(manifest.toString());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record SnapshotQueueResult(UUID snapshotId, int recordCount, int chunkCount, long watermark) { }
}

package com.enterprise.mm.service;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishes MM material-master revisions in the ERP Integration Core envelope. */
@Service
public class MaterialWmsIntegrationPublisher {

    private static final String EVENT_TYPE = "mm.material.changed.v1";
    private final IntegrationEventService integrationEventService;
    private final UnitOfMeasureRepository unitOfMeasureRepository;

    public MaterialWmsIntegrationPublisher(
        IntegrationEventService integrationEventService,
        UnitOfMeasureRepository unitOfMeasureRepository
    ) {
        this.integrationEventService = integrationEventService;
        this.unitOfMeasureRepository = unitOfMeasureRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Material material) {
        long revision = material.getVersion() == null ? 0L : material.getVersion();
        Map<String, Object> data = materialData(material);

        publish(material.getId(), revision, data, "WMS");
        publish(material.getId(), revision, data, "ERP");
    }

    /**
     * Queues one canonical MM material revision for WMS without replaying it to
     * any other downstream system.  This is used by the explicit WMS baseline
     * operation; normal material changes continue to use {@link #publish}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishToWms(Material material) {
        publishToWms(material, material.getId());
    }

    /** Queues one WMS material event and associates it with an explicit replay run. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishToWms(Material material, UUID correlationId) {
        long revision = material.getVersion() == null ? 0L : material.getVersion();
        publish(material.getId(), revision, materialData(material), "WMS",
            correlationId == null ? material.getId().toString() : correlationId.toString());
    }

    /** Reuses the live-event schema for snapshot records so replay and delta use one projection contract. */
    Map<String, Object> materialData(Material material) {
        long revision = material.getVersion() == null ? 0L : material.getVersion();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("canonicalId", material.getId());
        data.put("sku", material.getCode());
        data.put("name", material.getName());
        data.put("unit", unit(material));
        // MM has no governed QC-required attribute yet; LIMS decisions still control received lots.
        data.put("qualityControlRequired", false);
        data.put("active", "ACTIVE".equals(material.getStatus()) && !material.isDeleted());
        data.put("sourceRevision", revision);
        return data;
    }

    private void publish(UUID materialId, long revision, Map<String, Object> data, String targetSystem) {
        publish(materialId, revision, data, targetSystem, materialId.toString());
    }

    private void publish(
        UUID materialId,
        long revision,
        Map<String, Object> data,
        String targetSystem,
        String correlationId
    ) {
        UUID eventId = UUID.randomUUID();
        String target = targetSystem.toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId);
        envelope.put("source", "mm");
        envelope.put("target", target);
        envelope.put("type", EVENT_TYPE);
        envelope.put("subject", "materials/" + materialId);
        envelope.put("time", Instant.now());
        envelope.put("kind", "EVENT");
        envelope.put("schemaVersion", "1.0");
        envelope.put("aggregateType", "material");
        envelope.put("aggregateId", materialId);
        envelope.put("aggregateVersion", revision);
        envelope.put("idempotencyKey", EVENT_TYPE + ":" + target + ":" + materialId + ":" + revision);
        envelope.put("correlationId", correlationId);
        envelope.put("data", data);
        integrationEventService.emitInCurrentTransaction(EVENT_TYPE, targetSystem, materialId, Map.of(
            "targetPath", "/api/integration/envelopes",
            "raw", envelope
        ));
    }

    private String unit(Material material) {
        UnitOfMeasure unit = material.getUnitOfMeasure();
        if (unit == null && material.getUnitOfMeasureId() != null) {
            unit = unitOfMeasureRepository.findById(material.getUnitOfMeasureId()).orElse(null);
        }
        if (unit == null) {
            return "unit";
        }
        if (unit.getMeasurementNameShort() != null && !unit.getMeasurementNameShort().isBlank()) {
            return unit.getMeasurementNameShort();
        }
        return unit.getCode() == null || unit.getCode().isBlank() ? "unit" : unit.getCode();
    }
}

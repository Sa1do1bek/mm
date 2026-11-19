package com.enterprise.mm.service;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Source and reference stay distinct even when their display description contains separators. */
final class WarehouseStockFactMetadata {
    private static final ObjectMapper JSON = new ObjectMapper();
    private WarehouseStockFactMetadata() {}

    static String snapshot(String source, String reference) {
        return JSON.createObjectNode().put("source", clean(source)).put("reference", clean(reference)).toString();
    }

    static String fixedAssetSnapshot(String source, String reference) {
        return JSON.createObjectNode().put("source", clean(source)).put("reference", clean(reference))
            .put("materialType", "FIXED_ASSET").put("trackingMode", "QUANTITY").toString();
    }

    static String source(String payload, String legacyDescription) {
        // Older facts only retained their combined operation text; preserve it without guessing a split.
        if (payload == null || payload.isBlank()) return clean(legacyDescription);
        try {
            var source = JSON.readTree(payload).path("source");
            return source.isTextual() ? clean(source.asText()) : null;
        } catch (Exception ignored) {
            return clean(legacyDescription);
        }
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

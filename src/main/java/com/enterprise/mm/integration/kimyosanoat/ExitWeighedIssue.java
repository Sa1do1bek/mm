package com.enterprise.mm.integration.kimyosanoat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/** Conservative interpretation of the full Kimyosanoat transfer DTO for MM issue candidates. */
public record ExitWeighedIssue(long transferId, long warehouseId, long productId, BigDecimal quantityKg) {
    private static final Set<String> EXIT_STATUSES = Set.of(
        "EXIT_ACCEPTED", "EXIT_CONFIRMED", "EXIT_ENTERED", "EXIT_ENTERED_FACTORY");
    private static final Set<String> LEGACY_MASS_UNITS = Set.of(
        "kg", "кг", "kilogram", "kilograms", "килограмм", "t", "т", "tn", "тонна", "tonna", "ton", "tons");

    /** Returns null when the source does not prove an ordinary final, weighed mass issue. */
    public static ExitWeighedIssue from(JsonNode transfer) {
        if (transfer == null || !transfer.isObject()
            || !EXIT_STATUSES.contains(transfer.path("lastStatus").asText())
            || !contains(transfer.path("confirmedStatuses"), "EXIT_ACCEPTED")
            || (!transfer.path("administrativeClosure").isMissingNode() && !transfer.path("administrativeClosure").isNull())
            || transfer.path("reentryAllowed").asBoolean(false)
            || (!transfer.path("cylinderHandoverId").isMissingNode() && !transfer.path("cylinderHandoverId").isNull())
            || "INTERNAL".equals(transfer.path("transferType").asText())) return null;
        long id = positiveId(transfer.path("id"));
        long warehouse = positiveId(transfer.path("fromWarehouseId"));
        long product = positiveId(transfer.path("productId"));
        JsonNode quantity = transfer.path("confirmedNettoKg");
        if (id == 0 || warehouse == 0 || product == 0 || !quantity.isNumber()) return null;
        BigDecimal kg = quantity.decimalValue();
        if (kg.signum() <= 0) return null;
        // The field name is not proof of units for every source flow. Require source unit evidence.
        boolean versioned = !transfer.path("quantityModelVersion").isMissingNode() && !transfer.path("quantityModelVersion").isNull();
        if (versioned && !"MASS".equals(transfer.path("warehouseQuantity").path("dimension").asText())) return null;
        if (!versioned && !LEGACY_MASS_UNITS.contains(transfer.path("measureUnit").asText("").strip().toLowerCase(Locale.ROOT))) return null;
        return new ExitWeighedIssue(id, warehouse, product, kg);
    }

    private static boolean contains(JsonNode array, String value) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private static long positiveId(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0 ? node.longValue() : 0;
    }
}

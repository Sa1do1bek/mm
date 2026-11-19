package com.enterprise.mm.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic source-side calculation; no result here authorizes a stock posting. */
public final class FinishedGoodsOpeningCalculator {
    private static final Set<String> ACCOUNTS = Set.of("2810", "2811", "2812", "2813");
    private static final String ZERO_SOURCE = "00000000000000000000000000000000";
    record SourceRow(String sourceKey, String sourceHash, String accountCode, String warehouseSourceId,
                     String materialReferenceType, String materialSourceId, String uom,
                     UUID warehouseId, UUID materialId, BigDecimal quantity, BigDecimal amount,
                     boolean additionalDimensions) {}
    public record Key(String warehouseSourceId, String materialReferenceType, String materialSourceId, String uom) {}
    public record Position(Key key, UUID warehouseId, UUID materialId, BigDecimal quantity, BigDecimal amount,
                    String status, List<String> sourceKeys, List<SourceRow> sources) {}

    static List<Position> calculate(List<SourceRow> rows) {
        Objects.requireNonNull(rows, "rows");
        Map<Key, List<SourceRow>> groups = new LinkedHashMap<>();
        Set<String> sourceKeys = new HashSet<>();
        for (SourceRow row : rows) {
            Objects.requireNonNull(row, "source row");
            if (!ACCOUNTS.contains(row.accountCode())) continue;
            if (row.sourceKey() == null || row.sourceKey().isBlank() || !sourceKeys.add(row.sourceKey()))
                throw new IllegalArgumentException("Source balance key is missing or duplicated");
            if (row.sourceHash() == null || row.sourceHash().isBlank() || row.quantity() == null || row.amount() == null
                || row.warehouseSourceId() == null || row.materialReferenceType() == null
                || row.materialSourceId() == null || row.uom() == null || row.uom().isBlank())
                throw new IllegalArgumentException("Source balance has incomplete identity, hash, or value");
            Key key = new Key(row.warehouseSourceId(), row.materialReferenceType(), row.materialSourceId(), row.uom());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        Comparator<Key> keys = Comparator.comparing(Key::warehouseSourceId).thenComparing(Key::materialReferenceType)
            .thenComparing(Key::materialSourceId).thenComparing(Key::uom);
        return groups.entrySet().stream().sorted(Map.Entry.comparingByKey(keys)).map(entry -> {
            Key key = entry.getKey();
            List<SourceRow> items = entry.getValue().stream().sorted(Comparator.comparing(SourceRow::sourceKey)).toList();
            BigDecimal quantity = items.stream().map(SourceRow::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal amount = items.stream().map(SourceRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            Set<UUID> warehouses = new HashSet<>(), materials = new HashSet<>();
            items.forEach(item -> { warehouses.add(item.warehouseId()); materials.add(item.materialId()); });
            UUID warehouseId = warehouses.size() == 1 ? warehouses.iterator().next() : null;
            UUID materialId = materials.size() == 1 ? materials.iterator().next() : null;
            boolean invalidIdentity = key.warehouseSourceId().isBlank() || ZERO_SOURCE.equals(key.warehouseSourceId())
                || key.materialSourceId().isBlank() || ZERO_SOURCE.equals(key.materialSourceId())
                || !"00000037".equals(key.materialReferenceType());
            String status;
            if (invalidIdentity || warehouseId == null || materialId == null) status = "MAPPING_REVIEW";
            else if (items.stream().anyMatch(SourceRow::additionalDimensions)) status = "DIMENSIONS_REVIEW";
            else if (quantity.signum() < 0 || amount.signum() < 0) status = "NEGATIVE_REVIEW";
            else if (quantity.signum() == 0 && amount.signum() != 0) status = "VALUE_ONLY_REVIEW";
            else if (quantity.signum() == 0) status = "ZERO";
            else if (amount.signum() == 0) status = "ZERO_VALUE_REVIEW";
            else status = "LIVE_RECONCILIATION_REQUIRED";
            return new Position(key, warehouseId, materialId, quantity, amount, status,
                items.stream().map(SourceRow::sourceKey).toList(), items);
        }).toList();
    }

    private FinishedGoodsOpeningCalculator() {}
}

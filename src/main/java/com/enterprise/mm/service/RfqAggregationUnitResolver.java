package com.enterprise.mm.service;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.error.BusinessException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RfqAggregationUnitResolver {

    public String resolve(PurchaseRequestItem item) {
        String lineUnit = normalizeOptional(item == null ? null : item.getUnit()).orElse(null);
        if (lineUnit != null) {
            return lineUnit;
        }
        Material material = item == null ? null : item.getMaterial();
        UnitOfMeasure uom = material == null ? null : material.getUnitOfMeasure();
        String code = normalizeOptional(uom == null ? null : uom.getCode()).orElse(null);
        if (code != null) {
            return code;
        }
        String shortName = normalizeOptional(uom == null ? null : uom.getMeasurementNameShort()).orElse(null);
        if (shortName != null) {
            return shortName;
        }
        UUID itemId = item == null ? null : item.getId();
        throw new BusinessException(
            "Unit is required for RFQ aggregation",
            "rfq.aggregation.unit_required",
            itemId);
    }

    public Optional<String> normalizeOptional(String unit) {
        if (unit == null) {
            return Optional.empty();
        }
        String normalized = unit.trim();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    public String requiredKey(String unit, UUID sourceId) {
        return normalizeOptional(unit)
            .map(value -> value.toUpperCase(Locale.ROOT))
            .orElseThrow(() -> new BusinessException(
                "Unit is required for RFQ aggregation",
                "rfq.aggregation.unit_required",
                sourceId));
    }
}

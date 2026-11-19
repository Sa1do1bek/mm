package com.enterprise.mm.service;

import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MeasurementUnitType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.util.stream.Stream;

/** Pilot quantities use the material's existing stock unit; no implicit conversions or rounding. */
public final class StockUomPolicy {
    public static final int MAX_QUANTITY_SCALE = 7;
    private StockUomPolicy() {}

    public static void purchasing(Material material, String unit, BigDecimal quantity) {
        if (material == null) throw new BusinessException("Material not found");
        if (material.getMaterialType() != MaterialType.TMB) return;
        UnitOfMeasure uom = unit(material);
        if (unit == null || unit.isBlank() || Stream.of(uom.getCode(), uom.getMeasurementNameShort(),
                uom.getMeasurementNameShortRu(), uom.getMeasurementNameShortUz())
            .filter(java.util.Objects::nonNull).noneMatch(value -> value.trim().equalsIgnoreCase(unit.trim())))
            throw new BusinessException("Purchasing unit must match material stock UOM " + uom.getCode() + "; conversion is not supported");
        quantity(material, quantity, false);
    }

    public static void quantity(Material material, BigDecimal quantity, boolean allowZero) {
        UnitOfMeasure uom = unit(material);
        int precision = quantityScale(material);
        if (quantity == null || quantity.signum() < 0 || (!allowZero && quantity.signum() == 0))
            throw new BusinessException("Stock quantity must be " + (allowZero ? "nonnegative" : "positive"));
        BigDecimal normalized = quantity.stripTrailingZeros();
        if (normalized.precision() - normalized.scale() > 15)
            throw new BusinessException("Stock quantity exceeds 15 integer digits");
        if (normalized.scale() > precision)
            throw new BusinessException("Stock UOM " + uom.getCode() + " permits " + precision + " decimal places; quantity is not rounded");
    }

    /** Material precision is integral for required serial tracking even when its UOM allows fractions. */
    public static int quantityScale(Material material) {
        UnitOfMeasure uom = unit(material);
        return material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED ? 0 : effectiveScale(uom);
    }

    /** Nullable overrides retain the existing integral-piece/four-place stock policy. */
    public static int effectiveScale(UnitOfMeasure uom) {
        if (uom == null) throw new BusinessException("Material requires an approved stock UOM before stock execution");
        Integer configured = uom.getStockQuantityScale();
        if (configured != null) {
            if (configured < 0 || configured > MAX_QUANTITY_SCALE)
                throw new BusinessException("Stock quantity scale must be between 0 and 7");
            return configured;
        }
        return uom.getUnitType() == MeasurementUnitType.QUANTITY || "EA".equalsIgnoreCase(uom.getCode()) ? 0 : 4;
    }

    private static UnitOfMeasure unit(Material material) {
        if (material == null || material.getUnitOfMeasureId() == null || material.getUnitOfMeasure() == null
            || material.getUnitOfMeasure().isDeleted())
            throw new BusinessException("Material requires an approved stock UOM before stock execution");
        return material.getUnitOfMeasure();
    }
}

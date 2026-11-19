package com.enterprise.mm.common;

import com.enterprise.mm.entity.UnitOfMeasure;

/** Shared Uzbek display label for transfer quantities. Unit identity remains the UOM ID. */
public final class UnitOfMeasureLabels {
    private UnitOfMeasureLabels() {}

    public static String materialShortName(com.enterprise.mm.entity.Material material, java.util.Locale locale) {
        if (material == null || material.getTenantId() == null || material.getCompanyId() == null) return null;
        UnitOfMeasure unit = material.getUnitOfMeasure();
        if (unit == null || unit.isDeleted() || !material.getTenantId().equals(unit.getTenantId())
            || !material.getCompanyId().equals(unit.getCompanyId())) return null;
        return localizedShort(unit, locale);
    }

    public static String shortLabel(String value) {
        if (value == null) return null;
        String label = value.replaceFirst("(?i)\\s*\\[1C[^\\]]*\\]\\s*$", "").trim();
        if (label.isBlank() || label.matches("(?i)^(?:1C-UOM-|AMM-Reference).*")
            || BusinessDisplayNamePolicy.isInternalIdentifier(label)) return null;
        return label;
    }

    /** Human label in the report/UI language; imported identifiers are never display units. */
    public static String localizedShort(UnitOfMeasure unit, java.util.Locale locale) {
        if (unit == null) return null;
        String language = locale == null ? "en" : locale.getLanguage();
        String preferred = switch (language) {
            case "uz" -> unit.getMeasurementNameShortUz();
            case "ru" -> unit.getMeasurementNameShortRu();
            default -> unit.getMeasurementNameShort();
        };
        for (String value : new String[] {preferred, unit.getMeasurementNameShort(),
            unit.getMeasurementNameShortRu(), unit.getMeasurementNameShortUz(),
            LocalizedText.translate(unit.getName(), locale), unit.getCode()}) {
            if (value == null) continue;
            String label = shortLabel(value);
            if (label != null) return label;
        }
        return null;
    }

    public static String uzbekShort(UnitOfMeasure unit) {
        if (unit == null) return null;
        for (String value : new String[] {unit.getMeasurementNameShortUz(), unit.getMeasurementNameLongUz(),
            unit.getMeasurementNameShort(), unit.getMeasurementNameShortRu(), unit.getName(), unit.getCode()}) {
            String label = shortLabel(value);
            if (label != null) return label;
        }
        return null;
    }
}

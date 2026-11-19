package com.enterprise.mm.supply.workspace.enums;

public enum SupplyDueDateSource {
    MANUAL,
    EXPLICIT,
    POLICY;

    public static SupplyDueDateSource fromStoredValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "MANUAL" -> MANUAL;
            case "EXPLICIT" -> EXPLICIT;
            case "POLICY" -> POLICY;
            default -> throw new IllegalArgumentException(
                "No enum constant " + SupplyDueDateSource.class.getCanonicalName() + "." + value);
        };
    }

    public SupplyDueDateSource normalized() {
        return this == MANUAL ? EXPLICIT : this;
    }
}

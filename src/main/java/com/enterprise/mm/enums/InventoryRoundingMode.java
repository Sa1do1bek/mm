package com.enterprise.mm.enums;

import java.math.RoundingMode;

public enum InventoryRoundingMode {
    HALF_UP(RoundingMode.HALF_UP),
    HALF_EVEN(RoundingMode.HALF_EVEN),
    DOWN(RoundingMode.DOWN),
    UP(RoundingMode.UP);

    private final RoundingMode javaMode;

    InventoryRoundingMode(RoundingMode javaMode) {
        this.javaMode = javaMode;
    }

    public RoundingMode javaMode() {
        return javaMode;
    }
}

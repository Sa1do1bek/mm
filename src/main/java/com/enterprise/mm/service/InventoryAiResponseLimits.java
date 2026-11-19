package com.enterprise.mm.service;

/** ERP-side capacity limits for inventory AI extraction responses. */
final class InventoryAiResponseLimits {
    private InventoryAiResponseLimits() {}

    static int expanded(int original) {
        return Math.multiplyExact(original, 3);
    }
}

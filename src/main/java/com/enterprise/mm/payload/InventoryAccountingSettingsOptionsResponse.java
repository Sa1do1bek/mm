package com.enterprise.mm.payload;

import java.util.List;

public record InventoryAccountingSettingsOptionsResponse(
    List<Option> roundingModes,
    List<Option> periodCloseModes
) {
    public record Option(
        String value,
        String meaning,
        String example,
        boolean implemented
    ) {
    }
}

package com.enterprise.mm.fulfillment;

import java.util.List;

public final class FulfillmentLifecycle {
    private FulfillmentLifecycle() {
    }

    public static List<String> operationalStates() {
        return List.of("OPEN", "PARTIALLY_FULFILLED", "FULFILLED");
    }

    public static List<String> financialStates() {
        return List.of("UNINVOICED", "PARTIALLY_INVOICED", "INVOICED", "PARTIALLY_PAID", "PAID");
    }
}

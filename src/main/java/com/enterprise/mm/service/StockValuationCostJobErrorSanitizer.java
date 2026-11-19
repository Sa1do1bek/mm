package com.enterprise.mm.service;

public final class StockValuationCostJobErrorSanitizer {
    private static final int MAX_LENGTH = 1000;
    private static final String FALLBACK = "Valuation calculation failed";

    private StockValuationCostJobErrorSanitizer() {
    }

    public static String sanitizeError(Throwable throwable) {
        return sanitizeError(throwable == null ? null : throwable.getMessage());
    }

    public static String sanitizeError(String message) {
        if (message == null) {
            return FALLBACK;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return FALLBACK;
        }
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH) : trimmed;
    }
}

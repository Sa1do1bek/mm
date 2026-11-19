package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StockValuationCostJobErrorSanitizerTest {
    @Test
    void trimsLimitsAndFallsBackForBlankErrors() {
        assertThat(StockValuationCostJobErrorSanitizer.sanitizeError("  calculation failed  "))
            .isEqualTo("calculation failed");
        assertThat(StockValuationCostJobErrorSanitizer.sanitizeError(" "))
            .isEqualTo("Valuation calculation failed");
        assertThat(StockValuationCostJobErrorSanitizer.sanitizeError("x".repeat(1200)))
            .hasSize(1000);
    }
}

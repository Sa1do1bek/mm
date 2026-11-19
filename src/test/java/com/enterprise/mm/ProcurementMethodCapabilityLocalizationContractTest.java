package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class ProcurementMethodCapabilityLocalizationContractTest {
    private static final List<String> REASONS = List.of(
        "not_configured", "amount_required", "currency_required", "request_type_required", "category_required",
        "disabled", "method_not_enabled", "legacy", "deprecated", "not_yet_effective", "expired", "amount_below_minimum",
        "amount_above_maximum", "currency_not_allowed", "request_type_not_allowed", "category_not_allowed");

    @Test
    void everyCapabilityBlockerReasonResolvesInAllSupportedBundles() {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("ru"), Locale.forLanguageTag("uz"))) {
            ResourceBundle bundle = ResourceBundle.getBundle("message", locale);
            for (String suffix : REASONS) {
                String key = "procurement.method_capability." + suffix;
                assertThat(bundle.containsKey(key)).as("missing %s for %s", key, locale).isTrue();
                assertThat(bundle.getString(key)).isNotBlank();
            }
        }
    }
}

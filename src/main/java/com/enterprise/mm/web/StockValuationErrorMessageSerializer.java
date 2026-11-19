package com.enterprise.mm.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.regex.Pattern;
import org.springframework.context.i18n.LocaleContextHolder;

/** Localizes result errors on output, keeping persisted and internal errors unchanged. */
public class StockValuationErrorMessageSerializer extends JsonSerializer<String> {
    // Legacy job/run rows store text rather than an error code, so recognize their known reasons.
    private static final Pattern SHORTAGE = Pattern.compile(
        "Not enough stock for (FIFO|LIFO) valuation\\. Missing: ([0-9]+(?:\\.[0-9]+)?)");
    private static final String PREFIX = "valuation.result.error.";
    private static final Map<String, String> REASONS = Map.ofEntries(
        Map.entry("Specific batch costing requires stockBatchId for partial issues", "batch_required"),
        Map.entry("Stock batch not found for specific batch valuation", "batch_not_found"),
        Map.entry("Not enough stock in selected batch for specific batch valuation", "batch_shortage"),
        Map.entry("No stock for weighted average valuation", "no_average_stock"),
        Map.entry("No stock for multi-warehouse market valuation", "no_market_stock"),
        Map.entry("Material not found for valuation", "material_not_found"),
        Map.entry("Valuation quantity must be positive", "positive_quantity"),
        Map.entry("Approved market price not found for material", "market_price_not_found")
    );

    @Override
    public void serialize(String value, JsonGenerator generator, SerializerProvider provider) throws IOException {
        String language = LocaleContextHolder.getLocale().getLanguage();
        Locale locale = switch (language) {
            case "en" -> Locale.ROOT; // English lives in message.properties.
            case "ru" -> Locale.forLanguageTag("ru");
            default -> Locale.forLanguageTag("uz");
        };
        ResourceBundle messages = ResourceBundle.getBundle("message", locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        String original = value.trim();
        var shortage = SHORTAGE.matcher(original);
        if (shortage.matches()) {
            generator.writeString(messages.getString(PREFIX + "shortage")
                .replace("{method}", shortage.group(1)).replace("{quantity}", shortage.group(2)));
        } else if (original.startsWith("Unsupported valuation method: ")) {
            generator.writeString(messages.getString(PREFIX + "unsupported_method"));
        } else {
            // Unexpected technical failures get a translated fallback; raw details remain in storage.
            generator.writeString(messages.getString(PREFIX + REASONS.getOrDefault(original, "failed")));
        }
    }
}

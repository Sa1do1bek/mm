package com.enterprise.mm.service;

import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.payload.WarehouseReceiptContextDtos.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** A bounded, localized transport copy. Never changes the authoritative receipt snapshot. */
@Component
@RequiredArgsConstructor
public class ReceiptAdviceContextMapper {
    private final ObjectMapper mapper;

    public JsonNode map(Context context, String language) {
        ObjectNode result = mapper.valueToTree(context);
        text(result, "warehouseName", language);
        text(result, "warehousePurpose", language);
        for (JsonNode position : result.path("positions").path("content")) {
            text((ObjectNode) position, "name", language);
            for (JsonNode material : position.path("materials")) {
                for (String field : new String[]{"materialName", "materialDescription", "materialGroupName"})
                    text((ObjectNode) material, field, language);
            }
        }
        return result;
    }

    private void text(ObjectNode node, String field, String language) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) return;
        String result = LocalizedText.translate(value.textValue(), Locale.forLanguageTag(language));
        if (result != null && result.stripLeading().startsWith("{")) {
            try {
                JsonNode translations = mapper.readTree(result);
                for (String candidate : new String[]{language, "uz", "ru", "en"}) {
                    JsonNode translation = translations.get(candidate);
                    if (translation != null && translation.isTextual() && !translation.textValue().isBlank()) {
                        result = translation.textValue();
                        break;
                    }
                }
            } catch (Exception ignored) { /* Plain imported names are also valid. */ }
        }
        if (result != null && result.length() > 2000) {
            int end = Character.isHighSurrogate(result.charAt(1999)) ? 1999 : 2000;
            result = result.substring(0, end);
        }
        node.put(field, result);
    }

    /** AI may add optional fields, but cannot change any field we actually sent. */
    public boolean echoes(JsonNode expected, JsonNode actual) {
        if (actual == null) return false;
        if (expected.isNumber() && actual.isNumber())
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        if (expected.isObject() && actual.isObject()) {
            var fields = expected.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!echoes(field.getValue(), actual.get(field.getKey()))) return false;
            }
            return true;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) return false;
            for (int i = 0; i < expected.size(); i++)
                if (!echoes(expected.get(i), actual.get(i))) return false;
            return true;
        }
        return expected.equals(actual);
    }
}

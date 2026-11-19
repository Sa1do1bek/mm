package com.enterprise.mm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class IntegrationPayloadRedactor {
    private static final String REDACTED = "***REDACTED***";
    private static final int MAX_DEPTH = 12;
    private static final int MAX_ARRAY_ITEMS = 100;
    private static final int MAX_TEXT_LENGTH = 2_000;
    private static final Set<String> SENSITIVE_EXACT = Set.of(
        "authorization", "proxyauthorization", "password", "passwd", "pwd", "secret",
        "clientsecret", "apikey", "accesskey", "privatekey", "bearertoken", "accesstoken",
        "refreshtoken", "token", "signature", "cookie", "setcookie", "credential", "credentials"
    );
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
        "(?i)(password|passwd|pwd|secret|client[_-]?secret|api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|signature|cookie|credential)\\s*[:=]\\s*(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)"
    );
    private static final Pattern AUTH_VALUE = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");

    private final ObjectMapper objectMapper;

    public IntegrationPayloadRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode redactPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return redact(objectMapper.readTree(payload), null, 0);
        } catch (JsonProcessingException exception) {
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("format", "UNPARSEABLE");
            metadata.put("length", payload.length());
            metadata.put("preview", sanitizeText(payload));
            return metadata;
        }
    }

    public String sanitizeText(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String sanitized = KEY_VALUE_SECRET.matcher(value).replaceAll("$1=" + REDACTED);
        sanitized = AUTH_VALUE.matcher(sanitized).replaceAll("$1 " + REDACTED);
        if (sanitized.length() > MAX_TEXT_LENGTH) {
            sanitized = sanitized.substring(0, MAX_TEXT_LENGTH) + "…";
        }
        return sanitized;
    }

    public JsonNode responseMetadata(
        String status,
        Integer retryCount,
        String externalId,
        String lastError,
        Object nextRetryAt,
        Object lastAttemptAt
    ) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", status);
        node.put("attempts", retryCount == null ? 0 : retryCount);
        if (externalId != null && !externalId.isBlank()) node.put("externalReference", sanitizeText(externalId));
        if (lastError != null && !lastError.isBlank()) node.put("lastResult", sanitizeText(lastError));
        if (nextRetryAt != null) node.put("nextRetryAt", nextRetryAt.toString());
        if (lastAttemptAt != null) node.put("lastAttemptAt", lastAttemptAt.toString());
        return node;
    }

    private JsonNode redact(JsonNode source, String fieldName, int depth) {
        if (source == null || source.isNull()) return source;
        if (depth > MAX_DEPTH) return TextNode.valueOf("[TRUNCATED_DEPTH]");
        if (isSensitive(fieldName)) return TextNode.valueOf(REDACTED);
        if (source.isObject()) {
            ObjectNode target = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                target.set(field.getKey(), redact(field.getValue(), field.getKey(), depth + 1));
            }
            return target;
        }
        if (source.isArray()) {
            ArrayNode target = objectMapper.createArrayNode();
            int index = 0;
            for (JsonNode item : source) {
                if (index++ >= MAX_ARRAY_ITEMS) {
                    target.add("[TRUNCATED_ITEMS]");
                    break;
                }
                target.add(redact(item, fieldName, depth + 1));
            }
            return target;
        }
        if (source.isTextual()) {
            String text = source.asText();
            if (isUrlField(fieldName)) text = sanitizeUrl(text);
            return TextNode.valueOf(sanitizeText(text));
        }
        return source.deepCopy();
    }

    private boolean isSensitive(String fieldName) {
        if (fieldName == null) return false;
        String normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (SENSITIVE_EXACT.contains(normalized)) return true;
        return normalized.endsWith("password")
            || normalized.endsWith("secret")
            || normalized.endsWith("token")
            || normalized.endsWith("signature")
            || normalized.endsWith("credential");
    }

    private boolean isUrlField(String fieldName) {
        if (fieldName == null) return false;
        String normalized = fieldName.toLowerCase(Locale.ROOT);
        return normalized.contains("url") || normalized.contains("uri") || normalized.endsWith("endpoint");
    }

    private String sanitizeUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) return sanitizeText(value);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception exception) {
            return sanitizeText(value);
        }
    }
}

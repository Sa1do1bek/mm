package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/** Stable SHA-256 identity for a scoped posting request, independent of JSON field order. */
public final class PostingCommandFingerprint {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PostingCommandFingerprint() {
    }

    public static String sha256(PostingCommand command) {
        if (command == null) {
            throw new BadRequestException("Posting command is required");
        }
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("book", command.book());
        envelope.put("operation", command.operation());
        envelope.put("contextKey", command.contextKey());
        envelope.put("resourceId", command.resourceId().toString());
        envelope.put("postingDate", command.postingDate().toString());
        envelope.put("expectedVersion", command.expectedVersion());
        envelope.set("payload", canonical(command.payload()));
        try {
            byte[] bytes = MAPPER.writeValueAsString(canonical(envelope)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new BadRequestException("Cannot fingerprint posting command");
        }
    }

    private static JsonNode canonical(JsonNode source) {
        if (source.isObject()) {
            ObjectNode ordered = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            source.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> ordered.set(key, canonical(value)));
            return ordered;
        }
        if (source.isArray()) {
            ArrayNode ordered = JsonNodeFactory.instance.arrayNode();
            source.forEach(value -> ordered.add(canonical(value)));
            return ordered;
        }
        if (source.isNumber()) {
            return JsonNodeFactory.instance.numberNode(source.decimalValue().stripTrailingZeros());
        }
        return source.deepCopy();
    }
}

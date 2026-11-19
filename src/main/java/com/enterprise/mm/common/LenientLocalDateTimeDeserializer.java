package com.enterprise.mm.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = parser.getValueAsString();
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        try {
            if (normalized.length() == 10) {
                return LocalDate.parse(normalized).atStartOfDay();
            }
            return LocalDateTime.parse(normalized);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(normalized).toLocalDateTime();
            } catch (Exception offsetException) {
                try {
                    return Instant.parse(normalized).atZone(ZoneId.systemDefault()).toLocalDateTime();
                } catch (Exception instantException) {
                    throw context.weirdStringException(value, LocalDateTime.class, "Expected ISO date-time or date-only value");
                }
            }
        }
    }
}

package com.enterprise.mm.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Converter
public class UuidListConverter implements AttributeConverter<List<UUID>, String> {
    @Override
    public String convertToDatabaseColumn(List<UUID> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return attribute.stream()
            .map(UUID::toString)
            .distinct()
            .reduce((left, right) -> left + "," + right)
            .orElse(null);
    }

    @Override
    public List<UUID> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return Arrays.stream(dbData.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(UUID::fromString)
            .distinct()
            .toList();
    }
}

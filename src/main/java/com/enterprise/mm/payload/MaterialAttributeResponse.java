package com.enterprise.mm.payload;

import java.util.UUID;

public record MaterialAttributeResponse(
    UUID id,
    String key,
    String value,
    Integer sortOrder
) {
}

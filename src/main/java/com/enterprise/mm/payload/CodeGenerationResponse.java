package com.enterprise.mm.payload;

public record CodeGenerationResponse(
    String entityType,
    String prefix,
    String code
) {
}

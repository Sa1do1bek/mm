package com.enterprise.mm.payload;

import java.util.Map;

public record EnumOptionResponse(
    String code,
    Map<String, String> labels,
    int sortOrder,
    boolean active,
    Map<String, String> meta
) {
}

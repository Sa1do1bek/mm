package com.enterprise.mm.payload;

import java.util.UUID;

public record WarehouseIDAndNameDto(
    UUID id,
    String name
) {}

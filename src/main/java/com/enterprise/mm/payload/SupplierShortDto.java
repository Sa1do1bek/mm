package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record SupplierShortDto(UUID id,
                               String legalName,
                               String shortName,
                               String category,
                               BigDecimal rating,
                               String countryCode,
                               String status,
                               String blockedReason) {
}

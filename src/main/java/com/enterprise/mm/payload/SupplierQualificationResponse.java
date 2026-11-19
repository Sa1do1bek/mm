package com.enterprise.mm.payload;

import com.enterprise.mm.enums.SupplierQualificationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SupplierQualificationResponse(
    UUID supplierId,
    SupplierQualificationStatus status,
    String blockedReason,
    String blockedBy,
    LocalDateTime blockedAt,
    LocalDate expiresAt,
    List<Restriction> restrictions
) {
    public record Restriction(String type, String value, String reason) {
    }
}

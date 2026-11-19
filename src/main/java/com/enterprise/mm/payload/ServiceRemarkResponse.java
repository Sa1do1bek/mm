package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ServiceRemarkResponse(
    UUID id,
    String severity,
    String description,
    LocalDate dueDate,
    UUID assignedTo,
    String status,
    LocalDateTime createdAt
) {
}

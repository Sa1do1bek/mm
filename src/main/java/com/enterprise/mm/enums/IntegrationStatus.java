package com.enterprise.mm.enums;

import java.util.Locale;

public enum IntegrationStatus {
    NOT_CONFIGURED,
    READY,
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    RETRY_SCHEDULED,
    DEAD_LETTER,
    CANCELLED;

    public static IntegrationStatus fromLegacy(String status) {
        if (status == null || status.isBlank()) {
            return NOT_CONFIGURED;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "READY" -> READY;
            case "NEW", "PENDING" -> PENDING;
            case "PROCESSING", "IN_PROGRESS" -> IN_PROGRESS;
            case "PUBLISHED", "DELIVERED", "SUCCESS", "SUCCEEDED" -> SUCCEEDED;
            case "RETRY", "RETRY_SCHEDULED" -> RETRY_SCHEDULED;
            case "DEAD", "DEAD_LETTER" -> DEAD_LETTER;
            case "CANCELLED", "CANCELED" -> CANCELLED;
            case "FAILED" -> FAILED;
            default -> NOT_CONFIGURED;
        };
    }
}

package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.Map;

public record ApiErrorResponse(
    Object data,
    String message,
    String errorCode,
    Map<String, Object> details,
    LocalDateTime timestamp
) {
    public static ApiErrorResponse insufficientStock(Map<String, Object> details) {
        return new ApiErrorResponse(
            null,
            "Недостаточно доступного остатка",
            "INSUFFICIENT_AVAILABLE_STOCK",
            details,
            LocalDateTime.now()
        );
    }
}

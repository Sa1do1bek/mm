package com.enterprise.mm.payload;

import java.time.LocalDateTime;

public record ApiResponse<T>(
    T data,
    String message,
    String errorCode,
    LocalDateTime timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(data, "created", null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> message(String message) {
        return new ApiResponse<>(null, message, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> error(String message, String errorCode) {
        return new ApiResponse<>(null, message, errorCode, LocalDateTime.now());
    }

    /**
     * UX-LIVE-01: carries structured error context (e.g. the purchase order/supplier the
     * error occurred on) in {@code data} so the caller can keep the originating document
     * visible instead of losing it when an action fails.
     */
    public static <T> ApiResponse<T> error(String message, String errorCode, T data) {
        return new ApiResponse<>(data, message, errorCode, LocalDateTime.now());
    }
}

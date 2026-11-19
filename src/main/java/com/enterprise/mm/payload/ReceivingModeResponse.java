package com.enterprise.mm.payload;

public record ReceivingModeResponse(
    String mode,
    String targetModule,
    String blockerCode,
    String message
) {
}

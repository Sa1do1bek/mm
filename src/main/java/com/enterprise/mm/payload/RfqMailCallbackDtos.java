package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public final class RfqMailCallbackDtos {
    private RfqMailCallbackDtos() {
    }

    public record RfqMailEvent(
        @NotBlank String providerMessageId,
        @NotBlank String eventType,
        @NotNull Instant eventTimestamp
    ) {
    }
}

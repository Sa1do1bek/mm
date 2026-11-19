package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record FeedbackCreateRequest(
    @Size(max = 10000) String comment,
    @NotBlank String screenshotDataUrl,
    @Size(max = 1024) String route,
    OffsetDateTime createdAt,
    @Size(max = 4000) String userAgent,
    @Valid @NotNull FeedbackViewport viewport
) {
}

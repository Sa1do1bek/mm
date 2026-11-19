package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UserFeedbackResponse(
    UUID id,
    String code,
    String status,
    String comment,
    String route,
    OffsetDateTime clientCreatedAt,
    Integer viewportWidth,
    Integer viewportHeight,
    BigDecimal devicePixelRatio,
    String screenshotDataUrl,
    String userAgent,
    LocalDateTime createdAt
) {
}

package com.enterprise.mm.payload;

import com.enterprise.mm.entity.FcmToken;
import com.enterprise.mm.enums.FcmDevicePlatform;
import java.time.LocalDateTime;
import java.util.UUID;

public record FcmTokenResponse(
        UUID id,
        UUID userId,
        String token,
        FcmDevicePlatform platform,
        String deviceId,
        String language,
        boolean active,
        LocalDateTime lastSeenAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static FcmTokenResponse from(FcmToken entity) {
        return new FcmTokenResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getToken(),
                entity.getPlatform(),
                entity.getDeviceId(),
                entity.getLanguage(),
                entity.isActive(),
                entity.getLastSeenAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

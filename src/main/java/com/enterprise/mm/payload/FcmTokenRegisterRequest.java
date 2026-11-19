package com.enterprise.mm.payload;

import com.enterprise.mm.enums.FcmDevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FcmTokenRegisterRequest(
        @NotBlank String token,
        @NotNull FcmDevicePlatform platform,
        String deviceId,
        String language
) {
}

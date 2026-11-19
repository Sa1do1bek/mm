package com.enterprise.mm.payload;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.UUID;

public final class SupplierMagicLinkDtos {
    private SupplierMagicLinkDtos() {
    }

    public record MagicLinkExchangeRequest(@NotBlank String token) {
    }

    public record MagicLinkExchangeResponse(
        String token,
        LocalDateTime expiresAt,
        UUID portalAccountId,
        UUID supplierId,
        String supplierName,
        String companyCode,
        String status,
        String returnPath
    ) {
    }

    public record MagicLinkRequest(
        @NotBlank String companyCode,
        @Email @NotBlank String email
    ) {
    }

    public record MagicLinkRequestResponse(String message) {
    }
}

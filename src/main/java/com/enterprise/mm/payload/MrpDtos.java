package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class MrpDtos {
    private MrpDtos() {
    }

    public record MrpShortageDto(
        UUID materialId,
        String materialCode,
        String materialName,
        BigDecimal availableQuantity,
        BigDecimal openPurchaseRequestQuantity,
        BigDecimal minStock,
        BigDecimal safetyStock,
        BigDecimal reorderPoint,
        BigDecimal shortageQuantity,
        BigDecimal recommendedPurchaseQuantity,
        BigDecimal estimatedAmount
    ) {
    }

    public record MrpRunResultDto(
        int shortageCount,
        int purchaseRequestCount,
        BigDecimal estimatedTotalAmount,
        List<MrpShortageDto> shortages,
        List<UUID> purchaseRequestIds
    ) {
    }
}
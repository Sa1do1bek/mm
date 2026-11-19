package com.enterprise.mm.payload;

import com.enterprise.mm.payload.ContractAiDtos.ContractAiFindingDto;
import com.enterprise.mm.payload.ContractAiDtos.ContractAiRecommendationDto;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class PriceAiDtos {
    private PriceAiDtos() {
    }

    public record PriceAiAnalyzeRequest(
        @NotNull UUID materialId,
        UUID supplierId,
        UUID contractId,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal proposedUnitPrice,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
        String currencyCode,
        String providerSystem,
        Integer historyLimit
    ) {
    }

    public record PriceAiPricePointDto(
        String source,
        UUID sourceId,
        String sourceNumber,
        UUID supplierId,
        String supplierName,
        BigDecimal unitPrice,
        BigDecimal quantity,
        String currencyCode,
        LocalDate documentDate,
        String status
    ) {
    }

    public record PriceAiAnalyzeResponse(
        String providerSystem,
        String providerStatus,
        String providerMessage,
        String providerModel,
        UUID materialId,
        String materialName,
        BigDecimal proposedUnitPrice,
        BigDecimal minHistoricalPrice,
        BigDecimal maxHistoricalPrice,
        BigDecimal avgHistoricalPrice,
        BigDecimal lastPurchasePrice,
        BigDecimal quotationMinPrice,
        BigDecimal deviationFromAveragePercent,
        List<PriceAiPricePointDto> pricePoints,
        List<ContractAiFindingDto> findings,
        List<ContractAiRecommendationDto> recommendations,
        Object context,
        LocalDateTime generatedAt
    ) {
    }
}

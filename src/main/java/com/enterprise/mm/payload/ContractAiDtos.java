package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ContractAiDtos {
    private ContractAiDtos() {
    }

    public record ContractAiAnalyzeRequest(
        String providerSystem,
        Boolean includeRelatedDocuments,
        Boolean includeFinancials
    ) {
    }

    public record ContractAiFindingDto(
        String severity,
        String category,
        String title,
        String details
    ) {
    }

    public record ContractAiRecommendationDto(
        String priority,
        String action,
        String reason
    ) {
    }

    public record ContractAiAnalyzeResponse(
        String providerSystem,
        String providerStatus,
        String providerMessage,
        String providerModel,
        UUID contractId,
        String contractNumber,
        String supplierName,
        BigDecimal utilizationPercent,
        BigDecimal totalInvoices,
        BigDecimal totalPayments,
        BigDecimal unpaidAmount,
        int overdueInvoices,
        int expiredPowersOfAttorney,
        int overdueSchedules,
        List<ContractAiFindingDto> findings,
        List<ContractAiRecommendationDto> recommendations,
        Object context,
        LocalDateTime generatedAt
    ) {
    }
}
package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class PriceDiscoveryDtos {
    private PriceDiscoveryDtos() {
    }

    public record CreatePriceDiscoveryRfqRequest(
        LocalDate dueDate,
        String responsibleBuyer,
        String responseInstructions,
        String packageLanguage,
        List<UUID> invitedSupplierIds,
        UUID commissionId
    ) {
    }

    public record FinalizePriceDiscoveryRequest(@NotNull Long expectedVersion) {
    }

    public record CreateTenderRequest(
        LocalDate dueDate,
        String responsibleBuyer,
        String responseInstructions,
        String packageLanguage,
        List<UUID> invitedSupplierIds,
        UUID commissionId
    ) {
    }

    public record PriceDiscoveryQuotationView(
        UUID quotationId,
        UUID supplierId,
        BigDecimal totalAmount,
        String currencyCode,
        boolean included,
        String exclusionReason
    ) {
    }

    public record PriceDiscoveryPreview(
        UUID rfqId,
        UUID procurementProcedureId,
        BigDecimal provisionalAmount,
        String currencyCode,
        int validQuotationCount,
        List<PriceDiscoveryQuotationView> quotations
    ) {
        public PriceDiscoveryPreview {
            quotations = quotations == null ? List.of() : List.copyOf(quotations);
        }
    }

    public record FinalizePriceDiscoveryResponse(
        UUID calculationId,
        UUID rfqId,
        UUID procurementProcedureId,
        BigDecimal amount,
        String currencyCode,
        int quotationCount,
        LocalDateTime calculatedAt,
        List<PriceDiscoveryQuotationView> quotations
    ) {
        public FinalizePriceDiscoveryResponse {
            quotations = quotations == null ? List.of() : List.copyOf(quotations);
        }
    }

    public record CreateTenderResponse(
        UUID rfqId,
        UUID procurementProcedureId,
        UUID priceDiscoveryRfqId,
        UUID priceBasisCalculationId,
        BigDecimal amount,
        String currencyCode,
        String status
    ) {
    }
}

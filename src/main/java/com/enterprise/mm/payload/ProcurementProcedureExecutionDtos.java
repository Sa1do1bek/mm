package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class ProcurementProcedureExecutionDtos {
    private ProcurementProcedureExecutionDtos() {
    }

    public record AuctionAwardRequest(
        @NotNull UUID supplierId,
        @NotNull @Positive BigDecimal awardAmount,
        String currencyCode,
        String awardReference,
        String justification,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate
    ) {
    }

    public record FrameworkAwardRequest(
        @NotNull UUID supplierId,
        @NotNull @Positive BigDecimal limitAmount,
        @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
        String contractNumber,
        String awardReference,
        LocalDate startDate,
        LocalDate endDate
    ) {
    }

    public record FrameworkCallOffRequest(
        @NotNull @Positive BigDecimal callOffAmount,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate
    ) {
    }
}

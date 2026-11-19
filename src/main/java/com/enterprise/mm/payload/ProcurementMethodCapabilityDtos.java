package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ProcurementMethodCapabilityState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ProcurementMethodCapabilityDtos {
    private ProcurementMethodCapabilityDtos() {}

    public record UpsertRequest(
        @NotBlank @Size(max = 60)
        String methodCode,
        @NotNull
        ProcurementMethodCapabilityState state,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        @Pattern(regexp = "[A-Za-z]{3}")
        String currencyCode,
        Set<String> allowedRequestTypes,
        Set<String> allowedCategoryIds,
        boolean justificationRequired,
        boolean supplierRequired,
        boolean approvalRequired,
        Integer configurationVersion,
        @Size(max = 2000)
        String reason
    ) {}

    public record Response(
        UUID id,
        String tenantId,
        UUID companyId,
        String methodCode,
        ProcurementMethodCapabilityState state,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String currencyCode,
        Set<String> allowedRequestTypes,
        Set<String> allowedCategoryIds,
        boolean justificationRequired,
        boolean supplierRequired,
        boolean approvalRequired,
        Integer configurationVersion,
        String reason,
        LocalDateTime configuredAt,
        String configuredBy
    ) {}

    public record Evaluation(
        String methodCode,
        boolean available,
        ProcurementMethodCapabilityState state,
        List<String> blockingReasons,
        FrontendMetadata metadata
    ) {}

    public record FrontendMetadata(
        boolean showMethod,
        boolean showPilotBadge,
        boolean showLegacyBadge,
        boolean showDeprecatedBadge,
        boolean requiresJustification,
        boolean requiresSupplier,
        boolean requiresApproval,
        Integer configurationVersion
    ) {}
}

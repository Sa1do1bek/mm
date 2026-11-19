package com.enterprise.mm.payload;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** V2 keeps every contractual position visible, including unresolved group bindings. */
public final class WarehouseReceiptContextDtos {
    private WarehouseReceiptContextDtos() {}

    public record Readiness(boolean selectable, String reasonCode) {
        public static Readiness ready() { return new Readiness(true, null); }
        public static Readiness blocked(String reason) { return new Readiness(false, reason); }
    }
    public record Position(UUID specificationId, Long version, String name, String status, String kind,
        UUID contractMaterialGroupId, UUID contractUnitOfMeasureId, String contractUnit,
        BigDecimal contractQuantity, BigDecimal contractUnitPrice, BigDecimal remainingContractQuantity,
        Readiness readiness, long candidateCount,
        List<ContractManualIssueContextResponse.ContractManualIssueLineResponse> materials) {}

    public record Context(UUID contractId, String contractNumber, UUID warehouseId, String warehouseName,
        String warehousePurpose, Long profileVersion, PageResponse<Position> positions) {}

    /** This is an explicit, optional AI action. It never saves a selection or posts stock. */
    public record AiRequest(@NotNull UUID warehouseId, @Min(0) int page, @Min(0) @Max(25) int size,
        @Size(max = 20) String provider, @Size(max = 10) String language) {}

    public record AiSuggestion(UUID specificationId, UUID materialId, boolean recommendedSelected,
        double confidence, String reasonText, String reasonCode, boolean requiresMaterialChoice) {}
    public record AiAdvice(String status, String message, Context backendContext, List<AiSuggestion> suggestions,
        boolean complete, List<String> warnings, Integer retryAfterSeconds) {}
}

package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ManagementOpportunityBreakdownResponse(
    List<GroupAmount> byType,
    List<GroupAmount> byConfidence,
    List<EntityAmount> bySupplier,
    List<EntityAmount> byWarehouse,
    List<EntityAmount> byMaterial,
    List<EntityAmount> byMaterialGroup,
    List<CaseDrilldown> cases
) {
    public record GroupAmount(String group, String currencyCode, BigDecimal normalizedAmount, long caseCount) {
    }

    public record EntityAmount(UUID id, String currencyCode, BigDecimal normalizedAmount, long caseCount) {
    }

    public record CaseDrilldown(
        UUID id,
        String group,
        String type,
        String status,
        String completeness,
        String assignedRole,
        LocalDate asOfDate,
        String sourceAggregateType,
        UUID sourceAggregateId,
        UUID sourceLineId,
        String formulaCode,
        String formulaSnapshot,
        ManagementOpportunityFormulaResponse formula,
        String sourceCurrencyCode,
        BigDecimal sourceAmount,
        String normalizedCurrencyCode,
        BigDecimal normalizedAmount,
        List<UUID> evidenceIds,
        String drilldownUrl
    ) {
    }
}

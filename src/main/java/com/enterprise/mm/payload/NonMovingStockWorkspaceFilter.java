package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record NonMovingStockWorkspaceFilter(
    String search,
    List<String> statuses,
    UUID warehouseId,
    List<UUID> materialIds,
    LocalDate dateFrom,
    LocalDate dateTo,
    UUID responsibleUserId,
    Boolean overdueOnly,
    List<String> decisionTypes,
    List<String> executionStatuses,
    UUID materialGroupId,
    BigDecimal minBookValue,
    BigDecimal maxBookValue,
    List<String> blockerTypes,
    List<String> integrationStatuses,
    List<String> exclusionResults,
    Boolean activeCaseOnly
) {
    public String normalizedSearch() {
        return search == null || search.isBlank() ? null : search.trim();
    }

    public List<String> normalizedStatuses() {
        return statuses == null || statuses.isEmpty()
            ? List.of("__NO_STATUS_FILTER__")
            : statuses.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }

    public List<UUID> normalizedMaterialIds() {
        return materialIds == null || materialIds.isEmpty() ? List.of(new UUID(0L, 0L)) : materialIds;
    }

    public List<String> normalizedDecisionTypes() {
        return normalize(decisionTypes);
    }

    public List<String> normalizedExecutionStatuses() {
        return normalize(executionStatuses);
    }

    public List<String> normalizedBlockerTypes() {
        return normalize(blockerTypes);
    }

    public List<String> normalizedIntegrationStatuses() {
        return normalize(integrationStatuses);
    }

    public List<String> normalizedExclusionResults() {
        return normalize(exclusionResults);
    }

    public boolean hasStatuses() {
        return statuses != null && statuses.stream().anyMatch(value -> value != null && !value.isBlank());
    }

    public boolean hasMaterialIds() {
        return materialIds != null && !materialIds.isEmpty();
    }

    public boolean hasDecisionTypes() {
        return hasValues(decisionTypes);
    }

    public boolean hasExecutionStatuses() {
        return hasValues(executionStatuses);
    }

    public boolean hasBlockerTypes() {
        return hasValues(blockerTypes);
    }

    public boolean hasIntegrationStatuses() {
        return hasValues(integrationStatuses);
    }

    public boolean hasExclusionResults() {
        return hasValues(exclusionResults);
    }

    public boolean overdueOnlyEnabled() {
        return Boolean.TRUE.equals(overdueOnly);
    }

    public boolean activeCaseOnlyEnabled() {
        return Boolean.TRUE.equals(activeCaseOnly);
    }

    private List<String> normalize(List<String> values) {
        return values == null || values.isEmpty()
            ? List.of("__NO_FILTER__")
            : values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }

    private boolean hasValues(List<String> values) {
        return values != null && values.stream().anyMatch(value -> value != null && !value.isBlank());
    }
}

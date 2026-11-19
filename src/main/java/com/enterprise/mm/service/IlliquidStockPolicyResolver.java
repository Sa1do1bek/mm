package com.enterprise.mm.service;

import com.enterprise.mm.entity.IlliquidStockPolicy;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Pure, read-only governed policy selection shared by command views and policy preview. */
@Component
public class IlliquidStockPolicyResolver {
    public Optional<IlliquidStockPolicy> resolve(
        List<IlliquidStockPolicy> candidates,
        String legalEntityCode,
        UUID warehouseId,
        String materialCategory,
        String materialType
    ) {
        return candidates.stream()
            .filter(policy -> matches(policy.getLegalEntityCode(), legalEntityCode))
            .filter(policy -> matches(policy.getWarehouseId(), warehouseId))
            .filter(policy -> matches(normalize(policy.getMaterialCategory()), normalize(materialCategory)))
            .filter(policy -> matches(normalize(policy.getMaterialType()), normalize(materialType)))
            .sorted(Comparator
                .comparingInt(this::specificity).reversed()
                .thenComparing(IlliquidStockPolicy::getEffectiveFrom, Comparator.reverseOrder())
                .thenComparing(
                    IlliquidStockPolicy::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
            .findFirst();
    }

    private int specificity(IlliquidStockPolicy policy) {
        int score = 0;
        if (policy.getLegalEntityCode() != null && !policy.getLegalEntityCode().isBlank()) score++;
        if (policy.getWarehouseId() != null) score++;
        if (policy.getMaterialCategory() != null && !policy.getMaterialCategory().isBlank()) score++;
        if (policy.getMaterialType() != null && !policy.getMaterialType().isBlank()) score++;
        return score;
    }

    private <T> boolean matches(T policyValue, T requestedValue) {
        return policyValue == null || Objects.equals(policyValue, requestedValue);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

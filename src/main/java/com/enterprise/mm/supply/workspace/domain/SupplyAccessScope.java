package com.enterprise.mm.supply.workspace.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record SupplyAccessScope(
    String tenantId,
    Set<UUID> companyIds,
    Set<UUID> warehouseIds,
    boolean globalAdmin,
    boolean warehouseScoped
) {
    public SupplyAccessScope {
        tenantId = Objects.requireNonNull(tenantId, "tenantId is required").trim();
        if (tenantId.isEmpty()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        companyIds = companyIds == null ? Set.of() : Set.copyOf(companyIds);
        warehouseIds = warehouseIds == null ? Set.of() : Set.copyOf(warehouseIds);
        if (!globalAdmin && companyIds.isEmpty()) {
            throw new IllegalArgumentException("At least one authorized company is required");
        }
    }

    public boolean allowsCompany(UUID companyId) {
        return companyId != null && ((globalAdmin && companyIds.isEmpty()) || companyIds.contains(companyId));
    }

    public boolean allowsWarehouse(UUID warehouseId) {
        return warehouseId != null && (!warehouseScoped || warehouseIds.contains(warehouseId));
    }
}

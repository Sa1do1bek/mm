package com.enterprise.mm.supply.workspace.domain;

import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

public record SupplyWorkspaceQuery(
    String tenantId,
    Set<UUID> authorizedCompanyIds,
    Set<UUID> authorizedWarehouseIds,
    boolean warehouseScoped,
    Instant asOf,
    LocalDate asOfDate,
    ZoneId timeZone,
    LocalDate from,
    LocalDate to,
    SupplyLens lens,
    UUID ownerUserId,
    UUID companyId,
    UUID supplierId,
    UUID purchaseOrderId,
    UUID contractId,
    UUID warehouseId,
    String transportMode,
    String currencyCode,
    String status,
    SupplyRiskKey risk,
    SupplyQueueKey queue,
    String search,
    SupplyCommitmentSort sort,
    Set<UUID> authenticatedUserIds,
    String authenticatedUsername
) {
    public SupplyWorkspaceQuery {
        authorizedCompanyIds = authorizedCompanyIds == null ? Set.of() : Set.copyOf(authorizedCompanyIds);
        authorizedWarehouseIds = authorizedWarehouseIds == null ? Set.of() : Set.copyOf(authorizedWarehouseIds);
        authenticatedUserIds = authenticatedUserIds == null ? Set.of() : Set.copyOf(authenticatedUserIds);
        authenticatedUsername = authenticatedUsername == null || authenticatedUsername.isBlank()
            ? null
            : authenticatedUsername.trim();
    }

    /** Backward-compatible constructor for callers which do not bind a principal. */
    public SupplyWorkspaceQuery(
        String tenantId,
        Set<UUID> authorizedCompanyIds,
        Set<UUID> authorizedWarehouseIds,
        boolean warehouseScoped,
        Instant asOf,
        LocalDate asOfDate,
        ZoneId timeZone,
        LocalDate from,
        LocalDate to,
        SupplyLens lens,
        UUID ownerUserId,
        UUID companyId,
        UUID supplierId,
        UUID purchaseOrderId,
        UUID contractId,
        UUID warehouseId,
        String transportMode,
        String currencyCode,
        String status,
        SupplyRiskKey risk,
        SupplyQueueKey queue,
        String search,
        SupplyCommitmentSort sort
    ) {
        this(
            tenantId, authorizedCompanyIds, authorizedWarehouseIds, warehouseScoped,
            asOf, asOfDate, timeZone, from, to, lens, ownerUserId, companyId,
            supplierId, purchaseOrderId, contractId, warehouseId, transportMode,
            currencyCode, status, risk, queue, search, sort, Set.of(), null);
    }

    /** Backward-compatible constructor for callers which bind one principal ID. */
    public SupplyWorkspaceQuery(
        String tenantId,
        Set<UUID> authorizedCompanyIds,
        Set<UUID> authorizedWarehouseIds,
        boolean warehouseScoped,
        Instant asOf,
        LocalDate asOfDate,
        ZoneId timeZone,
        LocalDate from,
        LocalDate to,
        SupplyLens lens,
        UUID ownerUserId,
        UUID companyId,
        UUID supplierId,
        UUID purchaseOrderId,
        UUID contractId,
        UUID warehouseId,
        String transportMode,
        String currencyCode,
        String status,
        SupplyRiskKey risk,
        SupplyQueueKey queue,
        String search,
        SupplyCommitmentSort sort,
        UUID authenticatedUserId,
        String authenticatedUsername
    ) {
        this(
            tenantId, authorizedCompanyIds, authorizedWarehouseIds, warehouseScoped,
            asOf, asOfDate, timeZone, from, to, lens, ownerUserId, companyId,
            supplierId, purchaseOrderId, contractId, warehouseId, transportMode,
            currencyCode, status, risk, queue, search, sort,
            authenticatedUserId == null ? Set.of() : Set.of(authenticatedUserId),
            authenticatedUsername);
    }

    public SupplyWorkspaceQuery withQueue(SupplyQueueKey namedQueue) {
        return new SupplyWorkspaceQuery(
            tenantId, authorizedCompanyIds, authorizedWarehouseIds, warehouseScoped,
            asOf, asOfDate, timeZone, from, to, lens, ownerUserId, companyId,
            supplierId, purchaseOrderId, contractId, warehouseId, transportMode,
            currencyCode, status, risk, namedQueue, search, sort,
            authenticatedUserIds, authenticatedUsername);
    }

    public SupplyWorkspaceQuery withAuthenticatedPrincipal(UUID userId, String username) {
        return withAuthenticatedPrincipals(userId == null ? Set.of() : Set.of(userId), username);
    }

    public SupplyWorkspaceQuery withAuthenticatedPrincipals(Set<UUID> userIds, String username) {
        return new SupplyWorkspaceQuery(
            tenantId, authorizedCompanyIds, authorizedWarehouseIds, warehouseScoped,
            asOf, asOfDate, timeZone, from, to, lens, ownerUserId, companyId,
            supplierId, purchaseOrderId, contractId, warehouseId, transportMode,
            currencyCode, status, risk, queue, search, sort, userIds, username);
    }

    /** Compatibility accessor for code that can only represent one account. */
    public UUID authenticatedUserId() {
        return authenticatedUserIds.size() == 1 ? authenticatedUserIds.iterator().next() : null;
    }
}

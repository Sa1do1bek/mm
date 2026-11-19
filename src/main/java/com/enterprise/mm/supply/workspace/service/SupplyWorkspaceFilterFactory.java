package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SupplyWorkspaceFilterFactory {
    private static final long MAX_RANGE_DAYS = 366;

    private final Clock clock;
    private final ZoneId timeZone;

    public SupplyWorkspaceFilterFactory(
        Clock clock,
        @Value("${app.supply-workspace.time-zone:Asia/Tashkent}") String timeZone
    ) {
        this.clock = clock;
        this.timeZone = ZoneId.of(timeZone);
    }

    public SupplyWorkspaceQuery normalize(SupplyWorkspaceRequest requested, SupplyAccessScope scope) {
        if (scope == null) {
            throw bad("SUPPLY_SCOPE_REQUIRED", "Authenticated supply scope is required");
        }
        SupplyWorkspaceRequest request = requested == null ? new SupplyWorkspaceRequest() : requested;
        Instant asOf = request.getAsOf() == null ? clock.instant() : request.getAsOf();
        validateRange(request.getFrom(), request.getTo());

        UUID companyId = narrowCompany(request.getCompanyId(), scope);
        UUID warehouseId = narrowWarehouse(request.getWarehouseId(), scope);
        String currency = currency(request.getCurrencyCode());
        SupplyCommitmentSort sort = request.getSort() == null ? SupplyCommitmentSort.DEFAULT : request.getSort();
        if (sort == SupplyCommitmentSort.OPEN_AMOUNT && currency == null) {
            throw bad("SUPPLY_CURRENCY_REQUIRED", "currencyCode is required for OPEN_AMOUNT sorting");
        }

        SupplyLens lens = request.getLens() == null ? SupplyLens.TEAM : request.getLens();
        UUID ownerUserId = lens == SupplyLens.MY_WORK ? null : request.getOwnerUserId();
        return new SupplyWorkspaceQuery(
            scope.tenantId(), scope.companyIds(), scope.warehouseIds(), scope.warehouseScoped(), asOf,
            asOf.atZone(timeZone).toLocalDate(), timeZone, request.getFrom(), request.getTo(),
            lens, ownerUserId,
            companyId, request.getSupplierId(), request.getPurchaseOrderId(), request.getContractId(),
            warehouseId, upper(request.getTransportMode()), currency, upper(request.getStatus()),
            request.getRisk(), request.getQueue(), trim(request.getSearch()), sort);
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return;
        }
        if (from == null || to == null) {
            throw bad("SUPPLY_RANGE_INCOMPLETE", "Both from and to are required when filtering by range");
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days <= 0) {
            throw bad("SUPPLY_RANGE_INVALID", "to must be after from");
        }
        if (days > MAX_RANGE_DAYS) {
            throw bad("SUPPLY_RANGE_TOO_LARGE", "Supply workspace range cannot exceed 366 days");
        }
    }

    private UUID narrowCompany(UUID requested, SupplyAccessScope scope) {
        Set<UUID> authorized = scope.companyIds();
        if (requested != null) {
            if (!scope.allowsCompany(requested)) {
                throw bad("SUPPLY_COMPANY_FORBIDDEN", "Requested company is outside authenticated scope");
            }
            return requested;
        }
        return authorized.size() == 1 ? authorized.iterator().next() : null;
    }

    private UUID narrowWarehouse(UUID requested, SupplyAccessScope scope) {
        Set<UUID> authorized = scope.warehouseIds();
        if (requested != null) {
            if (!scope.allowsWarehouse(requested)) {
                throw bad("SUPPLY_WAREHOUSE_FORBIDDEN", "Requested warehouse is outside authenticated scope");
            }
            return requested;
        }
        return scope.warehouseScoped() && authorized.size() == 1 ? authorized.iterator().next() : null;
    }

    private String currency(String value) {
        String normalized = upper(value);
        if (normalized != null && !normalized.matches("[A-Z]{3}")) {
            throw bad("SUPPLY_CURRENCY_INVALID", "currencyCode must be a three-letter ISO code");
        }
        return normalized;
    }

    private String upper(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BadRequestException bad(String code, String message) {
        return new BadRequestException(code + ": " + message, "supply.workspace.request.invalid", code);
    }
}

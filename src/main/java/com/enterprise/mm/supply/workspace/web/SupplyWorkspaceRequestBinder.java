package com.enterprise.mm.supply.workspace.web;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCalendarLane;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceRequest;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceFilterFactory;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Binds HTTP inputs to the authenticated tenant/company/warehouse scope before
 * any Supply workspace service is invoked.
 */
@Component
public class SupplyWorkspaceRequestBinder {
    private static final Set<String> WAREHOUSE_SCOPED_ROLES = Set.of(
        "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER");
    private static final Set<String> COMPANY_WIDE_ROLES = Set.of(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST",
        "SUPPLIER_MANAGER", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "EXECUTIVE", "AUDITOR");

    private final SupplyWorkspaceFilterFactory filterFactory;
    private final RoleDataScopeService dataScopeService;
    private final SupplyCommitmentKeyCodec keyCodec;

    public SupplyWorkspaceRequestBinder(
        SupplyWorkspaceFilterFactory filterFactory,
        RoleDataScopeService dataScopeService,
        SupplyCommitmentKeyCodec keyCodec
    ) {
        this.filterFactory = filterFactory;
        this.dataScopeService = dataScopeService;
        this.keyCodec = keyCodec;
    }

    public SupplyWorkspaceQuery query(SupplyWorkspaceRequest request) {
        return filterFactory.normalize(request, scope());
    }

    public SupplyAccessScope scope() {
        boolean globalAdmin = com.enterprise.mm.security.AccessScope.globalAdmin();
        Set<UUID> companyIds = globalAdmin
            ? Set.of()
            : Set.of(CompanyContext.currentCompany());

        RoleDataScopeService.DataScope dataScope = dataScopeService.currentScope();
        boolean warehouseScoped = dataScope.warehouseScoped() || warehouseOnlyPrincipal();
        Set<UUID> warehouseIds = warehouseScoped && dataScope.warehouseId() != null
            ? Set.of(dataScope.warehouseId())
            : Set.of();
        return new SupplyAccessScope(
            TenantContext.currentTenant(), companyIds, warehouseIds, globalAdmin, warehouseScoped);
    }

    public Pageable pageable(int page, int size) {
        if (page < 0) {
            throw bad("SUPPLY_PAGE_INVALID", "Page index must not be negative");
        }
        if (size < 1 || size > PaginationUtils.MAX_PAGE_SIZE) {
            throw bad(
                "SUPPLY_PAGE_SIZE_INVALID",
                "Page size must be between 1 and " + PaginationUtils.MAX_PAGE_SIZE);
        }
        return org.springframework.data.domain.PageRequest.of(page, size);
    }

    public String commitmentKey(String encodedCommitmentKey) {
        try {
            keyCodec.decode(encodedCommitmentKey);
            return encodedCommitmentKey;
        } catch (IllegalArgumentException exception) {
            throw bad("SUPPLY_COMMITMENT_KEY_INVALID", exception.getMessage());
        }
    }

    public Set<SupplyCalendarLane> calendarSources(
        Collection<SupplyCalendarLane> sources,
        Collection<SupplyCalendarLane> bracketedSources
    ) {
        EnumSet<SupplyCalendarLane> result = EnumSet.noneOf(SupplyCalendarLane.class);
        if (sources != null) result.addAll(sources);
        if (bracketedSources != null) result.addAll(bracketedSources);
        return Set.copyOf(result);
    }

    private boolean warehouseOnlyPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(value -> value.startsWith("ROLE_") ? value.substring(5) : value)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return roles.stream().anyMatch(WAREHOUSE_SCOPED_ROLES::contains)
            && roles.stream().noneMatch(COMPANY_WIDE_ROLES::contains);
    }

    private BadRequestException bad(String code, String message) {
        return new BadRequestException(
            code + ": " + message,
            "supply.workspace.request.invalid",
            code);
    }
}

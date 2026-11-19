package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.security.WarehouseRoleAuthorities;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceSelectorDtos.CompanyOption;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceSelectorDtos.OwnerOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, scope-bound selector data for Supply workspace filters/actions. */
@Service
public class SupplyWorkspaceSelectorService {
    private static final List<RoleName> OWNER_ROLE_PRECEDENCE = List.of(
        RoleName.PROCUREMENT_SPECIALIST,
        RoleName.PROCUREMENT_MANAGER,
        RoleName.SUPPLIER_MANAGER,
        RoleName.WAREHOUSE_MANAGER,
        RoleName.MM_MANAGER);

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public SupplyWorkspaceSelectorService(
        CompanyRepository companyRepository,
        UserRepository userRepository
    ) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<CompanyOption> companies(SupplyAccessScope scope, String search) {
        requireScope(scope);
        List<Company> companies = scope.globalAdmin() && scope.companyIds().isEmpty()
            ? companyRepository.findByTenantIdAndIsDeletedFalseOrderByNameAsc(scope.tenantId())
            : scope.companyIds().stream()
                .map(id -> companyRepository.findByTenantIdAndIdAndIsDeletedFalse(scope.tenantId(), id)
                    .orElse(null))
                .filter(Objects::nonNull)
                .toList();
        String needle = normalized(search);
        return companies.stream()
            .filter(company -> matches(company.getCode(), company.getName(), needle))
            .sorted(Comparator
                .comparing(Company::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(Company::getCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .map(company -> new CompanyOption(
                company.getId(), company.getCode(), company.getName(), company.getStatus()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<OwnerOption> owners(
        SupplyAccessScope scope,
        UUID requestedCompanyId,
        String search
    ) {
        requireScope(scope);
        UUID companyId = selectorCompany(scope, requestedCompanyId);
        String normalizedSearch = trimmed(search);
        return userRepository.searchActiveCompanyUsers(
                scope.tenantId(), companyId, normalizedSearch, Pageable.unpaged())
            .getContent().stream()
            .filter(User::isEnabled)
            .filter(user -> scope.tenantId().equals(user.getTenantId()))
            .filter(user -> companyId.equals(user.getCompanyId()))
            .map(user -> owner(user, companyId))
            .filter(Objects::nonNull)
            .sorted(Comparator
                .comparing(OwnerOption::displayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(OwnerOption::username, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    private UUID selectorCompany(SupplyAccessScope scope, UUID requestedCompanyId) {
        if (requestedCompanyId != null) {
            if (!scope.allowsCompany(requestedCompanyId)) {
                throw bad(
                    "SUPPLY_COMPANY_FORBIDDEN",
                    "Requested company is outside authenticated scope");
            }
            return requestedCompanyId;
        }
        if (scope.companyIds().size() == 1) {
            return scope.companyIds().iterator().next();
        }
        throw bad(
            "SUPPLY_SELECTOR_COMPANY_REQUIRED",
            "companyId is required when more than one company is permitted");
    }

    private OwnerOption owner(User user, UUID companyId) {
        if (user.getRoles() == null) return null;
        RoleName role = OWNER_ROLE_PRECEDENCE.stream()
            .filter(candidate -> WarehouseRoleAuthorities.hasRole(user.getRoles(), candidate))
            .findFirst()
            .orElse(null);
        if (role == null) return null;
        String displayName = trimmed(user.getFullName());
        if (displayName == null) displayName = trimmed(user.getName());
        if (displayName == null) displayName = user.getUsername();
        return new OwnerOption(
            user.getId(), companyId, user.getUsername(), displayName,
            SupplyAssignmentRole.valueOf(role.name()));
    }

    private void requireScope(SupplyAccessScope scope) {
        if (scope == null) {
            throw bad("SUPPLY_SCOPE_REQUIRED", "Authenticated supply scope is required");
        }
    }

    private boolean matches(String code, String name, String needle) {
        if (needle == null) return true;
        return contains(code, needle) || contains(name, needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String normalized(String value) {
        String trimmed = trimmed(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BadRequestException bad(String code, String message) {
        return new BadRequestException(
            code + ": " + message,
            "supply.workspace.selector.invalid",
            code);
    }
}

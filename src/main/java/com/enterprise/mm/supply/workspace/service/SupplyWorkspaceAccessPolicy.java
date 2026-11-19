package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SupplyWorkspaceAccessPolicy {
    private static final Set<String> READ_ROLES = Set.of(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST",
        "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD", "WAREHOUSE_KEEPER",
        "SUPPLIER_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "BUDGET_CONTROLLER", "EXECUTIVE",
        "AUDITOR", "VIEWER");
    private static final Set<String> MANAGEMENT_ROLES = Set.of(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "EXECUTIVE",
        "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR");
    private final UserRepository userRepository;

    public SupplyWorkspaceAccessPolicy(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void requireReadable(SupplyLens lens) {
        Set<String> roles = currentRoles();
        if (roles.stream().noneMatch(READ_ROLES::contains)) {
            throw new AccessDeniedException("supply.workspace.read.denied");
        }
        if (lens == SupplyLens.MANAGEMENT && roles.stream().noneMatch(MANAGEMENT_ROLES::contains)) {
            throw new AccessDeniedException("supply.workspace.management.denied");
        }
    }

    /**
     * Binds MY_WORK to the authenticated user's active company accounts. URL owner fields are
     * deliberately ignored, so a caller cannot query another person's work by ID.
     */
    public SupplyWorkspaceQuery bindAuthenticatedIdentity(SupplyWorkspaceQuery query) {
        if (query == null || query.lens() != SupplyLens.MY_WORK) {
            return query;
        }
        Authentication authentication = authentication();
        String username = authentication.getName();
        List<User> candidates = userRepository
            .findAllByTenantIdAndUsernameAndIsDeletedFalse(query.tenantId(), username)
            .stream()
            .filter(User::isEnabled)
            .filter(user -> user.getCompanyId() != null
                && (query.authorizedCompanyIds().isEmpty()
                    || query.authorizedCompanyIds().contains(user.getCompanyId())))
            .filter(user -> query.companyId() == null || query.companyId().equals(user.getCompanyId()))
            .filter(this::hasReadableRole)
            .toList();
        if (candidates.isEmpty()) {
            throw new AccessDeniedException("supply.workspace.identity.denied");
        }
        Set<UUID> userIds = candidates.stream()
            .map(User::getId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
        if (userIds.isEmpty()) {
            throw new AccessDeniedException("supply.workspace.identity.denied");
        }
        return query.withAuthenticatedPrincipals(userIds, candidates.get(0).getUsername());
    }

    private boolean hasReadableRole(User user) {
        return user.getRoles() != null && user.getRoles().stream()
            .anyMatch(role -> READ_ROLES.contains(role.name()));
    }

    private Set<String> currentRoles() {
        Authentication authentication = authenticationOrNull();
        if (authentication == null) return Set.of();
        return authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .collect(Collectors.toUnmodifiableSet());
    }

    private Authentication authentication() {
        Authentication authentication = authenticationOrNull();
        if (authentication == null) {
            throw new AccessDeniedException("supply.workspace.authentication.required");
        }
        return authentication;
    }

    private Authentication authenticationOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() ? null : authentication;
    }
}

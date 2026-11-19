package com.enterprise.mm.service;

import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement for stock valuation actions (UX-LIVE-04).
 * Must stay aligned with {@link StockValuationService} entry points so a direct API
 * call cannot bypass the workspace's role gating.
 */
@Component
public class StockValuationActionPolicy {

    public static final List<String> MUTATE_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "FINANCE_CONTROLLER",
        "ACCOUNTANT"
    );

    private static final Set<String> KNOWN_ACTIONS = Set.of("RECALCULATE", "REVALUE", "IMPAIR");

    public void requireActionAllowed(String actionKey) {
        String normalized = normalize(actionKey);
        if (!KNOWN_ACTIONS.contains(normalized)) {
            throw new BusinessException(
                "Unknown stock valuation action: " + actionKey,
                "stockValuation.action.unknown",
                actionKey);
        }
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot perform stock valuation action " + normalized,
                "stockValuation.action.roleDenied",
                normalized);
        }
    }

    public boolean hasMutateRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return MUTATE_ROLES.stream().anyMatch(roles::contains);
    }

    private String normalize(String actionKey) {
        return actionKey == null ? "" : actionKey.trim().toUpperCase(Locale.ROOT);
    }
}

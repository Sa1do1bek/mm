package com.enterprise.mm.service;

import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement for integration health workspace actions (UX-LIVE-04).
 * Must stay aligned with the state gates in {@link IntegrationHealthCommandService}.
 */
@Component
public class IntegrationHealthActionPolicy {

    public static final List<String> MUTATE_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "INTEGRATION_MANAGER"
    );

    public static final List<String> VIEW_ROLES = List.of(
        "ADMIN",
        "MM_MANAGER",
        "INTEGRATION_MANAGER",
        "AUDITOR"
    );

    private static final Set<String> RETRY_STATES = Set.of("NEW", "FAILED", "RETRY", "DEAD");
    private static final Set<String> REPLAY_STATES = Set.of("PUBLISHED", "FAILED", "RETRY", "DEAD", "CANCELLED");
    private static final Set<String> CANCEL_STATES = Set.of("NEW", "FAILED", "RETRY", "DEAD");

    public void requireActionAllowed(IntegrationEvent event, String actionKey) {
        if (event == null) {
            throw new BusinessException(
                "Integration event is required for action " + actionKey,
                "integrationHealth.action.notFound");
        }
        String status = normalize(event.getStatus());
        switch (normalizeKey(actionKey)) {
            case "RETRY" -> requireRetryAllowed(status);
            case "REPLAY" -> requireReplayAllowed(status);
            case "CANCEL" -> requireCancelAllowed(status);
            default -> throw new BusinessException(
                "Unknown integration health action: " + actionKey,
                "integrationHealth.action.unknown",
                actionKey);
        }
    }

    private void requireRetryAllowed(String status) {
        if (!RETRY_STATES.contains(status)) {
            throw new BusinessException(
                "Action RETRY is not allowed for integration event status " + status,
                "integrationHealth.action.retryNotAllowed",
                status);
        }
        requireMutateRole("retry");
    }

    private void requireReplayAllowed(String status) {
        if (!REPLAY_STATES.contains(status)) {
            throw new BusinessException(
                "Action REPLAY is not allowed for integration event status " + status,
                "integrationHealth.action.replayNotAllowed",
                status);
        }
        requireMutateRole("replay");
    }

    private void requireCancelAllowed(String status) {
        if (!CANCEL_STATES.contains(status)) {
            throw new BusinessException(
                "Action CANCEL is not allowed for integration event status " + status,
                "integrationHealth.action.cancelNotAllowed",
                status);
        }
        requireMutateRole("cancel");
    }

    private void requireMutateRole(String action) {
        if (!hasMutateRole()) {
            throw new BusinessException(
                "Current role cannot " + action + " integration event",
                "integrationHealth.action.roleDenied");
        }
    }

    public boolean hasMutateRole() {
        return hasAnyRole(MUTATE_ROLES);
    }

    public boolean hasViewRole() {
        return hasAnyRole(VIEW_ROLES);
    }

    private boolean hasAnyRole(List<String> roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        Set<String> userRoles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        return roles.stream().anyMatch(userRoles::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeKey(String actionKey) {
        return actionKey == null ? "" : actionKey.trim().toUpperCase(Locale.ROOT);
    }
}

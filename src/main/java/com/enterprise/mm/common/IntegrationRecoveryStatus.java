package com.enterprise.mm.common;

import java.util.Locale;

/**
 * UX-LIVE-03: shared mapping from an {@code IntegrationEvent} status to a recovery-oriented
 * status that workspaces (goods income, integration health) can surface consistently when
 * showing a related business document's integration state.
 */
public final class IntegrationRecoveryStatus {
    public static final String RESOLVED = "RESOLVED";
    public static final String NEEDS_RECOVERY = "NEEDS_RECOVERY";
    public static final String RETRY_SCHEDULED = "RETRY_SCHEDULED";
    public static final String CANCELLED = "CANCELLED";
    public static final String OPEN = "OPEN";

    private IntegrationRecoveryStatus() {
    }

    public static String fromEventStatus(String status) {
        if (status == null || status.isBlank()) {
            return OPEN;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "PUBLISHED", "DELIVERED" -> RESOLVED;
            case "FAILED", "DEAD" -> NEEDS_RECOVERY;
            case "RETRY" -> RETRY_SCHEDULED;
            case "CANCELLED" -> CANCELLED;
            default -> OPEN;
        };
    }
}

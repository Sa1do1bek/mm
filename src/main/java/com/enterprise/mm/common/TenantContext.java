package com.enterprise.mm.common;

public final class TenantContext {
    public static final String DEFAULT_TENANT = "default";
    private static final ThreadLocal<String> CURRENT = ThreadLocal.withInitial(() -> DEFAULT_TENANT);

    private TenantContext() {
    }

    public static String currentTenant() {
        String tenantId = CURRENT.get();
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
    }

    public static void setTenant(String tenantId) {
        CURRENT.set(normalize(tenantId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String normalize(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId.trim();
    }

    public static boolean valid(String tenantId) {
        return tenantId != null && tenantId.matches("[A-Za-z0-9_.-]{1,80}");
    }
}

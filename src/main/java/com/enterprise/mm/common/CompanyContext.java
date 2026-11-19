package com.enterprise.mm.common;

import java.util.UUID;

public final class CompanyContext {
    public static final UUID DEFAULT_COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ThreadLocal<UUID> CURRENT = ThreadLocal.withInitial(() -> DEFAULT_COMPANY_ID);

    private CompanyContext() {
    }

    public static UUID currentCompany() {
        UUID companyId = CURRENT.get();
        return companyId == null ? DEFAULT_COMPANY_ID : companyId;
    }

    public static void setCompany(UUID companyId) {
        CURRENT.set(companyId == null ? DEFAULT_COMPANY_ID : companyId);
    }

    public static void setCompany(String companyId) {
        CURRENT.set(normalize(companyId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static UUID normalize(String companyId) {
        return companyId == null || companyId.isBlank() ? DEFAULT_COMPANY_ID : UUID.fromString(companyId.trim());
    }

    public static boolean valid(String companyId) {
        if (companyId == null || companyId.isBlank()) {
            return true;
        }
        try {
            UUID.fromString(companyId.trim());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
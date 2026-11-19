package com.enterprise.mm.payload;

import java.util.Set;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(String username, String password, String mfaCode) {
    }

    public record LoginResponse(String token, String username, String tenantId, UUID companyId, String companyName, Set<String> roles, boolean mfaRequired, String mfaMethod) {
    }

    public record MeResponse(
        String username,
        String fullName,
        String email,
        String tenantId,
        UUID companyId,
        String companyName,
        UUID departmentId,
        Set<String> roles,
        boolean enabled,
        boolean mfaEnabled,
        String mfaMethod,
        UUID assignedWarehouseId,
        String assignedWarehouseName,
        boolean isWarehouseRestricted,
        UUID userId,
        Set<UUID> accessibleWarehouseIds
    ) {
        public MeResponse(
            String username,
            String fullName,
            String email,
            String tenantId,
            UUID companyId,
            String companyName,
            UUID departmentId,
            Set<String> roles,
            boolean enabled,
            boolean mfaEnabled,
            String mfaMethod,
            UUID assignedWarehouseId,
            String assignedWarehouseName,
            boolean isWarehouseRestricted,
            UUID userId
        ) {
            this(username, fullName, email, tenantId, companyId, companyName, departmentId, roles,
                enabled, mfaEnabled, mfaMethod, assignedWarehouseId, assignedWarehouseName,
                isWarehouseRestricted, userId, Set.of());
        }

        public MeResponse(
            String username,
            String fullName,
            String email,
            String tenantId,
            UUID companyId,
            String companyName,
            UUID departmentId,
            Set<String> roles,
            boolean enabled,
            boolean mfaEnabled,
            String mfaMethod
        ) {
            this(username, fullName, email, tenantId, companyId, companyName, departmentId, roles, enabled, mfaEnabled, mfaMethod, null, null, false, null, Set.of());
        }
    }
}

package com.enterprise.mm.security;

import java.util.UUID;

/**
 * Closed projection used by bearer-token validation. It deliberately excludes
 * password material, profile data, and the EAGER role collection.
 */
public interface UserSecurityState {
    UUID getId();

    String getUsername();

    String getTenantId();

    UUID getCompanyId();

    boolean isEnabled();

    boolean isDeleted();

    long getTokenVersion();
}

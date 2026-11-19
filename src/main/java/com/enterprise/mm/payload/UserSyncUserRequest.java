package com.enterprise.mm.payload;

import java.time.Instant;
import java.util.Set;

public record UserSyncUserRequest(
    Long id,
    String username,
    String email,
    boolean enabled,
    Set<String> roles,
    Set<String> modules,
    Instant updatedAt
) {
}

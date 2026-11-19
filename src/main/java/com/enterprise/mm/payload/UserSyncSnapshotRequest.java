package com.enterprise.mm.payload;

import java.time.Instant;
import java.util.List;

public record UserSyncSnapshotRequest(
    Instant generatedAt,
    String sourceSystem,
    String moduleCode,
    List<UserSyncRoleRequest> roles,
    List<UserSyncUserRequest> users
) {
}

package com.enterprise.mm.payload;

import java.time.Instant;

public record UserSyncAckResponse(boolean accepted, String moduleCode, Instant acceptedAt, int users, int roles) {
}

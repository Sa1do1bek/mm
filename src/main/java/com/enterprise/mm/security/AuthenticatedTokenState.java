package com.enterprise.mm.security;

import java.util.UUID;

/**
 * Non-secret state retained on the authenticated request so refresh can
 * re-check the exact credential generation that authenticated the request.
 */
public record AuthenticatedTokenState(UUID userId, long tokenVersion) {
}

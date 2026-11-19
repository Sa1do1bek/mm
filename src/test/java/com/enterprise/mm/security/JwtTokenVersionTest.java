package com.enterprise.mm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.User;
import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class JwtTokenVersionTest {
    private static final String SECRET =
        "test-only-jwt-secret-that-is-long-enough-for-hmac-sha-signing";

    @Test
    void newlyIssuedTokenCarriesStableUserIdAndCredentialVersion() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setUsername("jwt-user");
        user.setTokenVersion(9);
        JwtService service = new JwtService(SECRET, 15);

        String token = service.generate(
            user,
            List.of(new SimpleGrantedAuthority("ROLE_VIEWER")),
            "tenant-jwt",
            companyId
        );
        Claims claims = service.parse(token);

        assertThat(claims.getSubject()).isEqualTo("jwt-user");
        assertThat(claims.get("uid", String.class)).isEqualTo(userId.toString());
        assertThat(((Number) claims.get("tokenVersion")).longValue()).isEqualTo(9);
        assertThat(claims.get("tenantId", String.class)).isEqualTo("tenant-jwt");
        assertThat(claims.get("companyId", String.class)).isEqualTo(companyId.toString());
        assertThat(claims.get("roles", List.class)).containsExactly("ROLE_VIEWER");
    }
}

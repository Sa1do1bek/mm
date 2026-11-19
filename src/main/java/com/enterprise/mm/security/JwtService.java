package com.enterprise.mm.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.ttl-minutes}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
    }

    public String generate(User user, Collection<? extends GrantedAuthority> authorities) {
        return generate(user, authorities, TenantContext.currentTenant(), CompanyContext.currentCompany());
    }

    public String generate(User user, Collection<? extends GrantedAuthority> authorities, String tenantId) {
        return generate(user, authorities, tenantId, CompanyContext.currentCompany());
    }

    public String generate(User user, Collection<? extends GrantedAuthority> authorities, String tenantId, UUID companyId) {
        if (user == null || user.getId() == null || user.getUsername() == null) {
            throw new IllegalArgumentException("Persisted user identity is required for JWT issuance");
        }
        Instant now = Instant.now();
        List<String> roles = authorities.stream().map(GrantedAuthority::getAuthority).toList();
        JwtBuilder builder = Jwts.builder()
            .subject(user.getUsername())
            .claim("roles", roles)
            .claim("tenantId", TenantContext.normalize(tenantId))
            .claim("uid", user.getId().toString())
            .claim("tokenVersion", user.getTokenVersion());
        if (companyId != null) {
            builder.claim("companyId", companyId.toString());
        }
        return builder
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlMinutes * 60)))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}

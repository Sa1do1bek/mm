package com.enterprise.mm.security;

import lombok.RequiredArgsConstructor;

import io.jsonwebtoken.Claims;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final ApiSecurityErrorHandler errorHandler;
    private final UserRepository userRepository;

    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                String tokenTenant = TenantContext.normalize(claims.get("tenantId", String.class));
                String headerTenant = request.getHeader(TenantFilter.TENANT_HEADER);
                if (headerTenant != null && !TenantContext.normalize(headerTenant).equals(tokenTenant)) {
                    errorHandler.writeError(response, HttpStatus.FORBIDDEN, "Tenant mismatch", "TenantMismatch");
                    return;
                }
                String tokenCompany = claims.get("companyId", String.class);
                String headerCompany = request.getHeader(TenantFilter.COMPANY_HEADER);
                if (tokenCompany != null && headerCompany != null && !CompanyContext.normalize(headerCompany).toString().equals(CompanyContext.normalize(tokenCompany).toString())) {
                    errorHandler.writeError(response, HttpStatus.FORBIDDEN, "Company mismatch", "CompanyMismatch");
                    return;
                }
                UUID tokenCompanyId = tokenCompany == null ? null : CompanyContext.normalize(tokenCompany);
                UserSecurityState state = resolveSecurityState(claims, tokenTenant, tokenCompanyId)
                    .orElseThrow(InvalidBearerTokenException::new);
                requireCurrentSecurityState(state, tokenTenant, tokenCompanyId);
                long presentedVersion = tokenVersion(claims);
                if (state.getTokenVersion() != presentedVersion) {
                    SecurityContextHolder.clearContext();
                    errorHandler.writeError(
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "Authentication token has been revoked",
                        "TOKEN_REVOKED"
                    );
                    return;
                }
                @SuppressWarnings("unchecked")
                List<String> roles = claims.get("roles", List.class);
                List<String> safeRoles = roles == null ? Collections.emptyList() : roles;
                boolean globalAdmin = state.getCompanyId() == null
                    && userRepository.isGlobalAdminSecurityState(state.getId());
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    state.getUsername(),
                    new AuthenticatedTokenState(state.getId(), presentedVersion),
                    WarehouseRoleAuthorities.expand(safeRoles)
                );
                auth.setDetails(new AccessScope.RequestScope(globalAdmin));
                TenantContext.setTenant(tokenTenant);
                CompanyContext.setCompany(tokenCompany != null ? tokenCompany : headerCompany);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (RuntimeException exception) {
                SecurityContextHolder.clearContext();
                errorHandler.writeError(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required",
                    "AUTHENTICATION_REQUIRED"
                );
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private Optional<UserSecurityState> resolveSecurityState(
        Claims claims,
        String tokenTenant,
        UUID tokenCompanyId
    ) {
        Object uidClaim = claims.get("uid");
        if (uidClaim != null) {
            if (!(uidClaim instanceof String uid) || uid.isBlank()) {
                throw new InvalidBearerTokenException();
            }
            try {
                return userRepository.findSecurityStateById(UUID.fromString(uid));
            } catch (IllegalArgumentException exception) {
                throw new InvalidBearerTokenException();
            }
        }
        String username = claims.getSubject();
        if (username == null || username.isBlank()) {
            throw new InvalidBearerTokenException();
        }
        return userRepository.findLegacySecurityState(tokenTenant, tokenCompanyId, username);
    }

    private void requireCurrentSecurityState(
        UserSecurityState state,
        String tokenTenant,
        UUID tokenCompanyId
    ) {
        if (!state.isEnabled()
            || state.isDeleted()
            || !Objects.equals(state.getTenantId(), tokenTenant)
            || !Objects.equals(state.getCompanyId(), tokenCompanyId)) {
            throw new InvalidBearerTokenException();
        }
    }

    private long tokenVersion(Claims claims) {
        Object claim = claims.get("tokenVersion");
        if (claim == null) {
            return 0L;
        }
        long version;
        if (claim instanceof Number number) {
            version = number.longValue();
        } else if (claim instanceof String text) {
            try {
                version = Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw new InvalidBearerTokenException();
            }
        } else {
            throw new InvalidBearerTokenException();
        }
        if (version < 0) {
            throw new InvalidBearerTokenException();
        }
        return version;
    }

    private static final class InvalidBearerTokenException extends RuntimeException {
    }
}

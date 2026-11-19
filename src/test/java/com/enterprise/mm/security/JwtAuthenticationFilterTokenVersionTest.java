package com.enterprise.mm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTokenVersionTest {
    private static final String SECRET =
        "test-only-jwt-secret-that-is-long-enough-for-hmac-sha-signing";

    @Mock private UserRepository userRepository;

    @ParameterizedTest
    @CsvSource({
        "WAREHOUSE_HEAD,WAREHOUSE_MANAGER", "WAREHOUSE_MANAGER,WAREHOUSE_HEAD",
        "WAREHOUSE_KEEPER,WAREHOUSE_OPERATOR", "WAREHOUSE_OPERATOR,WAREHOUSE_KEEPER"
    })
    void existingSingleRoleTokensReceiveEquivalentWarehouseAuthorities(String role, String equivalent) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        JwtService jwtService = new JwtService(SECRET, 15);
        String bearer = token("legacy-user", userId.toString(), 0L, "tenant-filter", companyId, List.of("ROLE_" + role));
        when(userRepository.findSecurityStateById(userId))
            .thenReturn(Optional.of(state(userId, companyId, 0, true, false)));

        filter(jwtService).doFilter(request(bearer), new MockHttpServletResponse(), (req, res) -> {});

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
            .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_" + role, "ROLE_" + equivalent);
        assertThat(CompanyContext.currentCompany()).isEqualTo(companyId);
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void rejectsAValidlySignedTokenWhoseCredentialVersionIsStale() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        User tokenUser = tokenUser(userId, companyId, 4);
        JwtService jwtService = new JwtService(SECRET, 15);
        String token = jwtService.generate(
            tokenUser,
            List.of(new SimpleGrantedAuthority("ROLE_VIEWER")),
            "tenant-filter",
            companyId
        );
        when(userRepository.findSecurityStateById(userId))
            .thenReturn(Optional.of(state(userId, companyId, 5, true, false)));

        MockHttpServletRequest request = request(token);
        request.setRequestURI("/api/auth/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter(jwtService).doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"TOKEN_REVOKED\"");
        assertThat(continued).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void matchingVersionBuildsSecurityContextOnlyAfterDatabaseValidation() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        JwtService jwtService = new JwtService(SECRET, 15);
        String token = jwtService.generate(
            tokenUser(userId, companyId, 3),
            List.of(new SimpleGrantedAuthority("ROLE_VIEWER")),
            "tenant-filter",
            companyId
        );
        when(userRepository.findSecurityStateById(userId))
            .thenReturn(Optional.of(state(userId, companyId, 3, true, false)));
        AtomicBoolean continued = new AtomicBoolean();

        filter(jwtService).doFilter(
            request(token),
            new MockHttpServletResponse(),
            (req, res) -> continued.set(true)
        );

        assertThat(continued).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
            .isEqualTo("legacy-user");
    }

    @Test
    void acceptsALegacyTokenWithoutUidOrVersionOnlyAtDatabaseVersionZero() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        JwtService jwtService = new JwtService(SECRET, 15);
        String legacyToken = token(
            "legacy-user", null, null, "tenant-filter", companyId, List.of("ROLE_VIEWER")
        );
        when(userRepository.findLegacySecurityState(
            "tenant-filter",
            companyId,
            "legacy-user"
        )).thenReturn(Optional.of(state(userId, companyId, 0, true, false)));

        MockHttpServletRequest request = request(legacyToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter(jwtService).doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(continued).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
            .isEqualTo("legacy-user");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getCredentials())
            .isEqualTo(new AuthenticatedTokenState(userId, 0));
    }

    @Test
    void malformedUidNeverFallsBackToUsername() throws Exception {
        UUID companyId = UUID.randomUUID();
        JwtService jwtService = new JwtService(SECRET, 15);
        String token = token(
            "legacy-user", "not-a-uuid", 0L, "tenant-filter", companyId, List.of("ROLE_VIEWER")
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter(jwtService).doFilter(request(token), response, (req, res) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"AUTHENTICATION_REQUIRED\"");
        assertThat(continued).isFalse();
    }

    @Test
    void versionlessLegacyTokenIsRevokedAfterFirstCredentialChange() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        JwtService jwtService = new JwtService(SECRET, 15);
        String legacyToken = token(
            "legacy-user", null, null, "tenant-filter", companyId, List.of("ROLE_VIEWER")
        );
        when(userRepository.findLegacySecurityState(
            "tenant-filter",
            companyId,
            "legacy-user"
        )).thenReturn(Optional.of(state(userId, companyId, 1, true, false)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(jwtService).doFilter(request(legacyToken), response, (req, res) -> {
            throw new AssertionError("revoked legacy token continued");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"TOKEN_REVOKED\"");
    }

    @Test
    void disabledAndDeletedUsersAreRejectedBeforeSecurityContextCreation() throws Exception {
        UUID disabledId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        JwtService jwtService = new JwtService(SECRET, 15);
        String disabledToken = jwtService.generate(
            tokenUser(disabledId, companyId, 0),
            List.of(),
            "tenant-filter",
            companyId
        );
        String deletedToken = jwtService.generate(
            tokenUser(deletedId, companyId, 0),
            List.of(),
            "tenant-filter",
            companyId
        );
        when(userRepository.findSecurityStateById(disabledId))
            .thenReturn(Optional.of(state(disabledId, companyId, 0, false, false)));
        when(userRepository.findSecurityStateById(deletedId))
            .thenReturn(Optional.of(state(deletedId, companyId, 0, true, true)));

        MockHttpServletResponse disabledResponse = new MockHttpServletResponse();
        filter(jwtService).doFilter(request(disabledToken), disabledResponse, (req, res) -> {
            throw new AssertionError("disabled token continued");
        });
        SecurityContextHolder.clearContext();
        MockHttpServletResponse deletedResponse = new MockHttpServletResponse();
        filter(jwtService).doFilter(request(deletedToken), deletedResponse, (req, res) -> {
            throw new AssertionError("deleted token continued");
        });

        assertThat(disabledResponse.getStatus()).isEqualTo(401);
        assertThat(deletedResponse.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void tenantAndCompanyHeaderMismatchesAreRejected() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        JwtService jwtService = new JwtService(SECRET, 15);
        String token = jwtService.generate(
            tokenUser(userId, companyId, 0),
            List.of(),
            "tenant-filter",
            companyId
        );
        MockHttpServletRequest tenantMismatch = request(token);
        tenantMismatch.removeHeader(TenantFilter.TENANT_HEADER);
        tenantMismatch.addHeader(TenantFilter.TENANT_HEADER, "other-tenant");
        MockHttpServletResponse tenantResponse = new MockHttpServletResponse();

        filter(jwtService).doFilter(tenantMismatch, tenantResponse, (req, res) -> {
            throw new AssertionError("tenant-mismatched token continued");
        });

        MockHttpServletRequest companyMismatch = request(token);
        companyMismatch.addHeader(TenantFilter.COMPANY_HEADER, UUID.randomUUID().toString());
        MockHttpServletResponse companyResponse = new MockHttpServletResponse();
        filter(jwtService).doFilter(companyMismatch, companyResponse, (req, res) -> {
            throw new AssertionError("company-mismatched token continued");
        });

        assertThat(tenantResponse.getStatus()).isEqualTo(403);
        assertThat(companyResponse.getStatus()).isEqualTo(403);
    }

    private JwtAuthenticationFilter filter(JwtService jwtService) {
        return new JwtAuthenticationFilter(
            jwtService,
            new ApiSecurityErrorHandler(new ObjectMapper().findAndRegisterModules()),
            userRepository
        );
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader(TenantFilter.TENANT_HEADER, "tenant-filter");
        return request;
    }

    private User tokenUser(UUID id, UUID companyId, long tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setUsername("legacy-user");
        user.setTenantId("tenant-filter");
        user.setCompanyId(companyId);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private String token(
        String subject,
        String uid,
        Long tokenVersion,
        String tenantId,
        UUID companyId,
        List<String> roles
    ) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
            .subject(subject)
            .claim("roles", roles)
            .claim("tenantId", tenantId)
            .claim("companyId", companyId.toString());
        if (uid != null) {
            builder.claim("uid", uid);
        }
        if (tokenVersion != null) {
            builder.claim("tokenVersion", tokenVersion);
        }
        return builder
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(900)))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();
    }

    private UserSecurityState state(
        UUID id,
        UUID companyId,
        long tokenVersion,
        boolean enabled,
        boolean deleted
    ) {
        return new UserSecurityState() {
            @Override public UUID getId() { return id; }
            @Override public String getUsername() { return "legacy-user"; }
            @Override public String getTenantId() { return "tenant-filter"; }
            @Override public UUID getCompanyId() { return companyId; }
            @Override public boolean isEnabled() { return enabled; }
            @Override public boolean isDeleted() { return deleted; }
            @Override public long getTokenVersion() { return tokenVersion; }
        };
    }
}

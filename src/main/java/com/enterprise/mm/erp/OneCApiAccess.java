package com.enterprise.mm.erp;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Deployment-pinned native 1C publication and company-scoped API credential. */
@Component
public class OneCApiAccess {
    private final String baseUrl;
    private final String apiKey;
    private final String tenant;
    private final String company;
    private final String username;
    private final String password;
    @org.springframework.beans.factory.annotation.Autowired
    private OneCTransport transport;

    @Autowired
    public OneCApiAccess(@Value("${mm.one-c-api.base-url:}") String baseUrl,
                         @Value("${mm.one-c-api.key:}") String apiKey,
                         @Value("${mm.one-c-api.tenant:}") String tenant,
                         @Value("${mm.one-c-api.company:}") String company,
                         @Value("${mm.one-c-api.username:}") String username,
                         @Value("${mm.one-c-api.password:}") String password) {
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.apiKey = apiKey;
        this.tenant = tenant; this.company = company;
        this.username = username; this.password = password;
    }

    public OneCApiAccess(String baseUrl, String apiKey, String tenant, String company) {
        this(baseUrl, apiKey, tenant, company, "", "");
    }

    public void authorize(HttpRequest.Builder request, String targetBase, String targetTenant, Object targetCompany) {
        if(transport!=null&&transport.pullEnabled())throw new IllegalArgumentException("Outbound native 1C HTTP is disabled in pull mode");
        requireConfiguredScope(targetTenant, String.valueOf(targetCompany));
        if (!baseUrl.equals(targetBase.replaceAll("/+$", ""))) throw new IllegalArgumentException("1C endpoint is not the configured native publication");
        URI uri = URI.create(baseUrl);
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())))
            throw new IllegalArgumentException("1C requires HTTPS or a local HTTP publication");
        // Legacy backend-initiated native publications still accept the API-key contract only.
        if (apiKey.length() < 32) throw new BadCredentialsException("Legacy outbound 1C requires an API key of at least 32 characters");
        request.header("X-MM-API-Key", apiKey);
        request.header("X-Tenant-Id", tenant).header("X-Company-Id", company);
    }

    public void authenticate(String suppliedKey) {
        requireConfiguredScope(TenantContext.currentTenant(), String.valueOf(CompanyContext.currentCompany()));
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.isAuthenticated() && current.getPrincipal() instanceof NativePrincipal principal
            && tenant.equals(principal.tenant()) && company.equals(principal.company())) return;
        requireLegacyKey(suppliedKey);
    }

    /** Only the native HTTP filter creates this identity; it has no browser/operator authorities. */
    public Authentication authenticateRequest(String authorization, String suppliedKey) {
        requireConfiguredScope(TenantContext.currentTenant(), String.valueOf(CompanyContext.currentCompany()));
        if (authorization == null) {
            requireLegacyKey(suppliedKey);
        } else {
            if (!basicConfigured() || authorization.length() > 8192
                || authorization.length() < 7 || authorization.chars().anyMatch(c -> c < 32 || c == 127)
                || !authorization.regionMatches(true, 0, "Basic ", 0, 6))
                throw new BadCredentialsException("Invalid 1C API credential");
            String decoded;
            try {
                decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(
                    Base64.getDecoder().decode(authorization.substring(6).trim()))).toString();
            } catch (IllegalArgumentException | CharacterCodingException exception) {
                throw new BadCredentialsException("Invalid 1C API credential");
            }
            int separator = decoded.indexOf(':');
            if (separator < 1) throw new BadCredentialsException("Invalid 1C API credential");
            boolean validUsername = same(username, decoded.substring(0, separator));
            boolean validPassword = same(password, decoded.substring(separator + 1));
            if (!(validUsername & validPassword)) throw new BadCredentialsException("Invalid 1C API credential");
        }
        return new UsernamePasswordAuthenticationToken(new NativePrincipal(tenant, company), null, List.of());
    }

    private void requireLegacyKey(String suppliedKey) {
        if (apiKey.length() < 32 || suppliedKey == null || !same(apiKey, suppliedKey))
            throw new BadCredentialsException("Invalid 1C API credential");
    }

    private static boolean same(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean basicConfigured() { return !username.isBlank() && !username.contains(":") && !password.isBlank(); }

    private record NativePrincipal(String tenant, String company) {}

    public void requireCurrentScope() { requireConfiguredScope(TenantContext.currentTenant(), String.valueOf(CompanyContext.currentCompany())); }

    private void requireConfiguredScope(String requestTenant, String requestCompany) {
        if ((!basicConfigured() && apiKey.length() < 32) || ((transport == null || !transport.pullEnabled()) && baseUrl.isBlank()) || tenant.isBlank() || company.isBlank()
            || !tenant.equals(requestTenant) || !company.equals(requestCompany))
            throw new BadCredentialsException("Native 1C API is disabled or scope does not match");
    }
}

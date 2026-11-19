package com.enterprise.mm.security;

import com.enterprise.mm.erp.OneCApiAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Deployment-scoped native 1C credentials, separate from browser JWT and Treasury. */
public final class OneCMachineAuthenticationFilter extends OncePerRequestFilter {
    private static final String PREFIX = "/api/integrations/one-c";
    private final OneCApiAccess access;
    private final ApiSecurityErrorHandler errors;

    public OneCMachineAuthenticationFilter(OneCApiAccess access, ApiSecurityErrorHandler errors) {
        this.access = access;
        this.errors = errors;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("GET".equals(request.getMethod())) return !path.equals(PREFIX + "/health");
        if (!"POST".equals(request.getMethod())) return true;
        return !(path.equals(PREFIX + "/events") || path.equals(PREFIX + "/tasks/poll")
            || path.matches(PREFIX + "/tasks/[^/]+/result") || path.startsWith(PREFIX + "/warehouse/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var previous = SecurityContextHolder.getContext();
        var machineContext = SecurityContextHolder.createEmptyContext();
        try {
            // TenantFilter has already validated and installed these headers. Never use its default scope implicitly.
            if (request.getHeader(TenantFilter.TENANT_HEADER) == null || request.getHeader(TenantFilter.COMPANY_HEADER) == null
                || request.getHeader(TenantFilter.COMPANY_HEADER).isBlank())
                throw new BadCredentialsException("Native 1C scope headers are required");
            var authorizations = request.getHeaders("Authorization");
            String authorization = authorizations.hasMoreElements() ? authorizations.nextElement() : null;
            if (authorizations.hasMoreElements()) throw new BadCredentialsException("Ambiguous 1C API credential");
            machineContext.setAuthentication(access.authenticateRequest(authorization, request.getHeader("X-MM-API-Key")));
        } catch (BadCredentialsException exception) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"MM 1C API\", charset=\"UTF-8\"");
            errors.writeError(response, HttpStatus.UNAUTHORIZED, "Authentication required", "AUTHENTICATION_REQUIRED");
            return;
        }
        SecurityContextHolder.setContext(machineContext);
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.setContext(previous); }
    }
}

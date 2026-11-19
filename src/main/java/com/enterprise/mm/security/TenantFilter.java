package com.enterprise.mm.security;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.CompanyContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {
    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String COMPANY_HEADER = "X-Company-Id";
    private final ApiSecurityErrorHandler errorHandler;

    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // Servlet threads are reused; never inherit a request-local drilldown scope.
        AccessScope.clearRequestedCompanyScope();
        String tenantId = request.getHeader(TENANT_HEADER);
        String companyId = request.getHeader(COMPANY_HEADER);
        String[] requestedCompanyScopes = request.getParameterValues(CompanyScope.PARAMETER);
        if (tenantId != null && !TenantContext.valid(tenantId)) {
            errorHandler.writeError(response, HttpStatus.BAD_REQUEST, "Invalid tenant id", "InvalidTenantId");
            return;
        }
        if (companyId != null && !CompanyContext.valid(companyId)) {
            errorHandler.writeError(response, HttpStatus.BAD_REQUEST, "Invalid company id", "InvalidCompanyId");
            return;
        }
        if (requestedCompanyScopes != null) {
            for (String requestedCompanyScope : requestedCompanyScopes) {
                if (CompanyScope.fromRequestValue(requestedCompanyScope) == null) {
                    errorHandler.writeError(
                        response,
                        HttpStatus.BAD_REQUEST,
                        "Invalid company scope",
                        "InvalidCompanyScope"
                    );
                    return;
                }
            }
        }
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        if (requestedCompanyScopes != null) {
            AccessScope.setRequestedCompanyScope(CompanyScope.CURRENT_COMPANY);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            CompanyContext.clear();
            AccessScope.clearRequestedCompanyScope();
        }
    }
}

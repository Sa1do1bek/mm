package com.enterprise.mm.security;

import com.enterprise.mm.common.CompanyContext;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AccessScope {
    private static final ThreadLocal<CompanyScope> REQUESTED_COMPANY_SCOPE = new ThreadLocal<>();

    private AccessScope() {
    }

    public static boolean companyScoped(boolean companyScoped) {
        return companyScoped && (currentCompanyRequested() || !globalAdmin());
    }

    public static boolean canAccessCompany(UUID companyId) {
        return (!currentCompanyRequested() && globalAdmin())
            || java.util.Objects.equals(CompanyContext.currentCompany(), companyId);
    }

    public static void setRequestedCompanyScope(CompanyScope companyScope) {
        if (companyScope == null) {
            REQUESTED_COMPANY_SCOPE.remove();
        } else {
            REQUESTED_COMPANY_SCOPE.set(companyScope);
        }
    }

    public static boolean currentCompanyRequested() {
        return REQUESTED_COMPANY_SCOPE.get() == CompanyScope.CURRENT_COMPANY;
    }

    public static void clearRequestedCompanyScope() {
        REQUESTED_COMPANY_SCOPE.remove();
    }

    public static boolean globalAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getDetails() instanceof RequestScope scope && scope.globalAdmin();
    }

    public record RequestScope(boolean globalAdmin) {
    }
}

package com.enterprise.mm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentCompanyScopeContractTest {
    private final UUID currentCompany = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CompanyContext.setCompany(currentCompany);
        var authentication = new UsernamePasswordAuthenticationToken(
            "global-admin",
            null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
        CompanyContext.clear();
    }

    @Test
    void globalAdminRemainsTenantWideUnlessCurrentCompanyWasExplicitlyRequested() {
        UUID otherCompany = UUID.randomUUID();

        assertThat(AccessScope.companyScoped(true)).isFalse();
        assertThat(AccessScope.canAccessCompany(otherCompany)).isTrue();

        AccessScope.setRequestedCompanyScope(CompanyScope.CURRENT_COMPANY);

        assertThat(AccessScope.companyScoped(true)).isTrue();
        assertThat(AccessScope.companyScoped(false)).isFalse();
        assertThat(AccessScope.canAccessCompany(currentCompany)).isTrue();
        assertThat(AccessScope.canAccessCompany(otherCompany)).isFalse();
    }

    @Test
    void tenantFilterAcceptsOnlyTheNamedScopeAndAlwaysClearsRequestState() throws Exception {
        TenantFilter filter = new TenantFilter(
            new ApiSecurityErrorHandler(new ObjectMapper().findAndRegisterModules()));
        MockHttpServletRequest valid = new MockHttpServletRequest();
        valid.setParameter("companyScope", "CURRENT_COMPANY");
        MockHttpServletResponse validResponse = new MockHttpServletResponse();
        AtomicBoolean scopedInsideChain = new AtomicBoolean();

        filter.doFilter(valid, validResponse, (request, response) ->
            scopedInsideChain.set(AccessScope.currentCompanyRequested()));

        assertThat(validResponse.getStatus()).isEqualTo(200);
        assertThat(scopedInsideChain).isTrue();
        assertThat(AccessScope.currentCompanyRequested()).isFalse();

        MockHttpServletRequest invalid = new MockHttpServletRequest();
        invalid.setParameter("companyScope", currentCompany.toString());
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        AtomicBoolean invalidChainCalled = new AtomicBoolean();

        filter.doFilter(invalid, invalidResponse, (request, response) ->
            invalidChainCalled.set(true));

        assertThat(invalidResponse.getStatus()).isEqualTo(400);
        assertThat(invalidResponse.getContentAsString()).contains("InvalidCompanyScope");
        assertThat(invalidChainCalled).isFalse();
    }

    @Test
    void currentCompanyDrilldownBuilderPreservesQueriesAndFragmentsWithoutDuplicatingScope() {
        assertThat(CompanyScope.currentCompanyDrilldown("/app/procurement-command"))
            .isEqualTo("/app/procurement-command?companyScope=CURRENT_COMPANY");
        assertThat(CompanyScope.currentCompanyDrilldown("/app/procurement-command?queue=OVERDUE#case"))
            .isEqualTo("/app/procurement-command?queue=OVERDUE&companyScope=CURRENT_COMPANY#case");
        assertThat(CompanyScope.currentCompanyDrilldown(
            "/app/procurement-command?queue=OVERDUE&companyScope=CURRENT_COMPANY"))
            .isEqualTo("/app/procurement-command?queue=OVERDUE&companyScope=CURRENT_COMPANY");
        assertThat(CompanyScope.currentCompanyDrilldown(null)).isNull();
    }
}

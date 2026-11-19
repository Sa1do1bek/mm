package com.enterprise.mm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.service.SsoAssertionService;
import com.enterprise.mm.service.SsoSettingsService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class WarehouseRoleAuthoritiesTest {
    @Mock UserRepository users;
    @Mock CompanyRepository companies;
    @Mock SsoSettingsService ssoSettings;
    @Mock PasswordEncoder encoder;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @CsvSource({
        "WAREHOUSE_HEAD,WAREHOUSE_MANAGER", "WAREHOUSE_MANAGER,WAREHOUSE_HEAD",
        "WAREHOUSE_KEEPER,WAREHOUSE_OPERATOR", "WAREHOUSE_OPERATOR,WAREHOUSE_KEEPER"
    })
    void passwordAuthenticationGrantsOnlyTheEquivalentWarehousePair(String storedRole, String equivalent) {
        User user = new User();
        user.setUsername("warehouse-user");
        user.setCompanyId(UUID.randomUUID());
        user.setEnabled(true);
        user.setPasswordHash("test-hash");
        user.setRoles(Set.of(RoleName.valueOf(storedRole)));
        when(users.findAllByTenantIdAndUsernameAndIsDeletedFalse(TenantContext.currentTenant(), "warehouse-user"))
            .thenReturn(List.of(user));

        var details = new DatabaseUserDetailsService(users).loadUserByUsername("warehouse-user");

        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_" + storedRole, "ROLE_" + equivalent);
        assertThat(user.getRoles()).containsExactly(RoleName.valueOf(storedRole));
    }

    @Test
    void departmentHeadInheritsLeadershipButNeitherManagerNorWorkerInheritsDepartmentHead() {
        assertThat(WarehouseRoleAuthorities.expand(List.of("ROLE_DEPARTMENT_HEAD")))
            .extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_DEPARTMENT_HEAD", "ROLE_WAREHOUSE_MANAGER", "ROLE_WAREHOUSE_HEAD");
        assertThat(WarehouseRoleAuthorities.expand(List.of("ROLE_DEPARTMENT_WORKER")))
            .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_DEPARTMENT_WORKER");
        assertThat(WarehouseRoleAuthorities.expand(List.of("ROLE_WAREHOUSE_MANAGER")))
            .extracting(GrantedAuthority::getAuthority).doesNotContain("ROLE_DEPARTMENT_HEAD");
    }

    @Test
    void repeatedExpansionDoesNotDuplicateAuthoritiesOrInheritOtherRoleFamilies() {
        var once = WarehouseRoleAuthorities.expand(List.of("ROLE_WAREHOUSE_KEEPER", "ROLE_WAREHOUSE_OPERATOR", "ROLE_ACCOUNTANT"));
        var twice = WarehouseRoleAuthorities.expand(once.stream().map(GrantedAuthority::getAuthority).toList());

        assertThat(twice).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_WAREHOUSE_KEEPER", "ROLE_WAREHOUSE_OPERATOR", "ROLE_ACCOUNTANT");
        assertThat(WarehouseRoleAuthorities.expand(List.of("ROLE_VIEWER")))
            .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_VIEWER");
    }

    @ParameterizedTest
    @CsvSource({
        "WAREHOUSE_HEAD,WAREHOUSE_MANAGER", "WAREHOUSE_MANAGER,WAREHOUSE_HEAD",
        "WAREHOUSE_KEEPER,WAREHOUSE_OPERATOR", "WAREHOUSE_OPERATOR,WAREHOUSE_KEEPER"
    })
    void ssoLoginResponseAndTokenUseEquivalentAuthoritiesWithoutChangingStoredRoles(String role, String equivalent) {
        UUID company = UUID.randomUUID();
        var runtimeSettings = new SsoSettingsService.RuntimeSsoSettings(
            true, "OIDC", null, null, null, null, null, null, null, null,
            List.of(), false, 60, null, null, List.of(role), null, null, null, null, null, null
        );
        when(ssoSettings.runtime("warehouse-sso", company)).thenReturn(runtimeSettings);
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            assertThat(user.getRoles()).containsExactly(RoleName.valueOf(role));
            return user;
        });
        JwtService jwt = new JwtService("test-only-warehouse-sso-secret-long-enough-for-hmac-signing", 15);
        var response = new SsoAssertionService(ssoSettings, users, companies, encoder, jwt)
            .authenticateClaims("warehouse-sso", company, "warehouse-sso-user", null, "Warehouse SSO user", List.of(role), "SSO");

        assertThat(response.roles()).containsExactlyInAnyOrder("ROLE_" + role, "ROLE_" + equivalent);
        assertThat(jwt.parse(response.token()).get("roles", List.class))
            .containsExactlyInAnyOrder("ROLE_" + role, "ROLE_" + equivalent);
        assertThat(response.companyId()).isEqualTo(company);
    }
}

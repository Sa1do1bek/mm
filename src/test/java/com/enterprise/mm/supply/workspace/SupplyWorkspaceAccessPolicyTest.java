package com.enterprise.mm.supply.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.supply.workspace.domain.SupplyAccessScope;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceRequest;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceAccessPolicy;
import com.enterprise.mm.supply.workspace.service.SupplyWorkspaceFilterFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SupplyWorkspaceAccessPolicyTest {
    private final SupplyWorkspaceAccessPolicy policy = new SupplyWorkspaceAccessPolicy(
        mock(UserRepository.class));

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedUserIsDenied() {
        assertThatThrownBy(() -> policy.requireReadable(SupplyLens.TEAM))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void readOnlyAndOperationalRolesCanReadTeamButNotManagement() {
        authenticate("viewer", "ROLE_VIEWER");
        policy.requireReadable(SupplyLens.TEAM);
        assertThatThrownBy(() -> policy.requireReadable(SupplyLens.MANAGEMENT))
            .isInstanceOf(AccessDeniedException.class);

        authenticate("accountant", "ROLE_ACCOUNTANT");
        policy.requireReadable(SupplyLens.TEAM);
        assertThatThrownBy(() -> policy.requireReadable(SupplyLens.MANAGEMENT))
            .isInstanceOf(AccessDeniedException.class);

        authenticate("buyer", "ROLE_PROCUREMENT_SPECIALIST");
        policy.requireReadable(SupplyLens.TEAM);
        assertThatThrownBy(() -> policy.requireReadable(SupplyLens.MANAGEMENT))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void managerCanReadManagementLens() {
        authenticate("manager", "ROLE_PROCUREMENT_MANAGER");
        policy.requireReadable(SupplyLens.MANAGEMENT);
    }

    @Test
    void myWorkBindsOwnerIdsAcrossEveryAuthorizedCompanyAccount() {
        String tenant = "tenant-multi-account";
        UUID firstCompany = UUID.randomUUID();
        UUID secondCompany = UUID.randomUUID();
        UUID firstUserId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        SupplyWorkspaceAccessPolicy multiCompanyPolicy = new SupplyWorkspaceAccessPolicy(users);
        when(users.findAllByTenantIdAndUsernameAndIsDeletedFalse(tenant, "buyer.shared"))
            .thenReturn(List.of(
                user(firstUserId, tenant, firstCompany, "buyer.shared"),
                user(secondUserId, tenant, secondCompany, "buyer.shared")));
        authenticate("buyer.shared", "ROLE_PROCUREMENT_SPECIALIST");

        SupplyWorkspaceQuery bound = multiCompanyPolicy.bindAuthenticatedIdentity(query(
            tenant, new SupplyAccessScope(
                tenant, Set.of(firstCompany, secondCompany), Set.of(), false, false)));

        assertThat(bound.authenticatedUserIds()).containsExactlyInAnyOrder(firstUserId, secondUserId);
        assertThat(bound.authenticatedUsername()).isEqualTo("buyer.shared");
    }

    @Test
    void globalAdminEmptyCompanyScopeStillBindsAuthenticatedIdentity() {
        String tenant = "tenant-global-admin";
        UUID company = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        SupplyWorkspaceAccessPolicy globalPolicy = new SupplyWorkspaceAccessPolicy(users);
        when(users.findAllByTenantIdAndUsernameAndIsDeletedFalse(tenant, "admin.shared"))
            .thenReturn(List.of(user(userId, tenant, company, "admin.shared")));
        authenticate("admin.shared", "ROLE_ADMIN");

        SupplyWorkspaceQuery bound = globalPolicy.bindAuthenticatedIdentity(query(
            tenant, new SupplyAccessScope(tenant, Set.of(), Set.of(), true, false)));

        assertThat(bound.authenticatedUserIds()).containsExactly(userId);
    }

    private SupplyWorkspaceQuery query(String tenant, SupplyAccessScope scope) {
        SupplyWorkspaceRequest request = new SupplyWorkspaceRequest();
        request.setLens(SupplyLens.MY_WORK);
        request.setAsOf(Instant.parse("2026-07-15T08:00:00Z"));
        return new SupplyWorkspaceFilterFactory(
            Clock.fixed(request.getAsOf(), ZoneOffset.UTC), "Asia/Tashkent")
            .normalize(request, scope);
    }

    private User user(UUID id, String tenant, UUID company, String username) {
        User user = new User();
        user.setId(id);
        user.setTenantId(tenant);
        user.setCompanyId(company);
        user.setUsername(username);
        user.setEnabled(true);
        user.setRoles(new java.util.LinkedHashSet<>(List.of(RoleName.PROCUREMENT_SPECIALIST)));
        return user;
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username, "n/a", List.of(new SimpleGrantedAuthority(role))));
    }
}

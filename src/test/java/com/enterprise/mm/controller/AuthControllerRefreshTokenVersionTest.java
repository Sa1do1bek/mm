package com.enterprise.mm.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.PasswordSecurityException;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import com.enterprise.mm.security.JwtService;
import com.enterprise.mm.service.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class AuthControllerRefreshTokenVersionTest {
    private static final String SECRET =
        "test-only-jwt-secret-that-is-long-enough-for-hmac-sha-signing";

    @Mock private AuthenticationManager authenticationManager;
    @Mock private CompanyRepository companyRepository;
    @Mock private UserRepository userRepository;
    @Mock private TotpService totpService;
    @Mock private SmsMfaService smsMfaService;
    @Mock private AuditService auditService;
    @Mock private CompanyService companyService;
    @Mock private PasswordChangeService passwordChangeService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ProfileSelfService profileSelfService;

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void meReturnsTheAuthenticatedUserIdForRequestDefaults() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-refresh");
        CompanyContext.setCompany(companyId);
        User user = new User();
        user.setId(userId);
        user.setTenantId("tenant-refresh");
        user.setCompanyId(companyId);
        user.setUsername("requester");
        user.setEnabled(true);
        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(companyRepository.findNameByTenantIdAndIdAndIsDeletedFalse("tenant-refresh", companyId))
            .thenReturn(Optional.of("Company"));
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());
        var authentication = new UsernamePasswordAuthenticationToken(
            "requester", new AuthenticatedTokenState(userId, 0),
            List.of(new SimpleGrantedAuthority("ROLE_DEPARTMENT_WORKER"))
        );
        AuthController controller = new AuthController(authenticationManager, new JwtService(SECRET, 15),
            companyRepository, userRepository, totpService, smsMfaService, auditService, companyService,
            passwordChangeService, roleDataScopeService, warehouseRepository, profileSelfService);

        var me = controller.me(authentication).data();
        assertThat(me.userId()).isEqualTo(userId);
        assertThat(me.assignedWarehouseId()).isNull();
        assertThat(me.accessibleWarehouseIds()).isEmpty();
    }

    @Test
    void meReturnsMultipleWarehouseGrantsWithoutRequiringASelectedWarehouse() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID firstWarehouseId = UUID.randomUUID();
        UUID secondWarehouseId = UUID.randomUUID();
        TenantContext.setTenant("tenant-refresh");
        CompanyContext.setCompany(companyId);
        User user = new User();
        user.setId(userId);
        user.setTenantId("tenant-refresh");
        user.setCompanyId(companyId);
        user.setUsername("warehouse-user");
        user.setEnabled(true);
        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(companyRepository.findNameByTenantIdAndIdAndIsDeletedFalse("tenant-refresh", companyId))
            .thenReturn(Optional.of("Company"));
        when(roleDataScopeService.currentScope()).thenReturn(new RoleDataScopeService.DataScope(
            false, false, true, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), null,
            null, java.util.Set.of(firstWarehouseId, secondWarehouseId)));
        var authentication = new UsernamePasswordAuthenticationToken(
            "warehouse-user", new AuthenticatedTokenState(userId, 0),
            List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_OPERATOR"))
        );
        AuthController controller = new AuthController(authenticationManager, new JwtService(SECRET, 15),
            companyRepository, userRepository, totpService, smsMfaService, auditService, companyService,
            passwordChangeService, roleDataScopeService, warehouseRepository, profileSelfService);

        var me = controller.me(authentication).data();

        assertThat(me.assignedWarehouseId()).isNull();
        assertThat(me.assignedWarehouseName()).isNull();
        assertThat(me.isWarehouseRestricted()).isTrue();
        assertThat(me.accessibleWarehouseIds()).containsExactlyInAnyOrder(firstWarehouseId, secondWarehouseId);
    }

    @Test
    void meReportsOnlyTheDirectEmployeeWarehouseAsAssigned() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        TenantContext.setTenant("tenant-refresh");
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        Employee employee = new Employee();
        employee.setWarehouse(warehouse);
        User user = new User();
        user.setId(userId);
        user.setTenantId("tenant-refresh");
        user.setCompanyId(companyId);
        user.setUsername("assigned-user");
        user.setEnabled(true);
        user.setEmployee(employee);
        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(companyRepository.findNameByTenantIdAndIdAndIsDeletedFalse("tenant-refresh", companyId))
            .thenReturn(Optional.of("Company"));
        when(roleDataScopeService.currentScope()).thenReturn(new RoleDataScopeService.DataScope(
            false, false, true, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), null,
            warehouseId, java.util.Set.of(warehouseId)));
        var authentication = new UsernamePasswordAuthenticationToken(
            "assigned-user", new AuthenticatedTokenState(userId, 0),
            List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_OPERATOR"))
        );
        AuthController controller = new AuthController(authenticationManager, new JwtService(SECRET, 15),
            companyRepository, userRepository, totpService, smsMfaService, auditService, companyService,
            passwordChangeService, roleDataScopeService, warehouseRepository, profileSelfService);

        var me = controller.me(authentication).data();

        assertThat(me.assignedWarehouseId()).isEqualTo(warehouseId);
        assertThat(me.accessibleWarehouseIds()).containsExactly(warehouseId);
    }

    @Test
    void refreshIssuesUidAndCurrentDatabaseTokenVersion() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-refresh");
        CompanyContext.setCompany(companyId);
        User user = new User();
        user.setId(userId);
        user.setTenantId("tenant-refresh");
        user.setCompanyId(companyId);
        user.setUsername("refresh-user");
        user.setEnabled(true);
        user.setTokenVersion(8);
        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(companyRepository.findNameByTenantIdAndIdAndIsDeletedFalse(
            "tenant-refresh",
            companyId
        )).thenReturn(Optional.of("Refresh Company"));
        var authentication = new UsernamePasswordAuthenticationToken(
            "refresh-user",
            new AuthenticatedTokenState(userId, 8),
            List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
        );
        JwtService jwtService = new JwtService(SECRET, 15);
        AuthController controller = new AuthController(
            authenticationManager,
            jwtService,
            companyRepository,
            userRepository,
            totpService,
            smsMfaService,
            auditService,
            companyService,
            passwordChangeService,
            roleDataScopeService,
            warehouseRepository,
            profileSelfService
        );

        var response = controller.refresh(authentication);
        var claims = jwtService.parse(response.data().token());

        assertThat(claims.get("uid", String.class)).isEqualTo(userId.toString());
        assertThat(((Number) claims.get("tokenVersion")).longValue()).isEqualTo(8);
    }

    @Test
    void refreshLosingThePasswordChangeRaceCannotMintANewToken() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-refresh");
        CompanyContext.setCompany(companyId);
        User user = new User();
        user.setId(userId);
        user.setTenantId("tenant-refresh");
        user.setCompanyId(companyId);
        user.setUsername("refresh-user");
        user.setEnabled(true);
        user.setTokenVersion(9);
        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        var authentication = new UsernamePasswordAuthenticationToken(
            "refresh-user",
            new AuthenticatedTokenState(userId, 8),
            List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
        );
        AuthController controller = new AuthController(
            authenticationManager,
            new JwtService(SECRET, 15),
            companyRepository,
            userRepository,
            totpService,
            smsMfaService,
            auditService,
            companyService,
            passwordChangeService,
            roleDataScopeService,
            warehouseRepository,
            profileSelfService
        );

        assertThatThrownBy(() -> controller.refresh(authentication))
            .isInstanceOfSatisfying(PasswordSecurityException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo("TOKEN_REVOKED")
            );
    }
}

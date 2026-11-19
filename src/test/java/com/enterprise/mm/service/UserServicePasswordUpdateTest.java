package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.mapper.UserMapper;
import com.enterprise.mm.payload.UserCreateRequest;
import com.enterprise.mm.payload.UserUpdateRequest;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServicePasswordUpdateTest {
    @Mock private UserRepository repository;
    @Mock private UserMapper mapper;
    @Mock private AuditService auditService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PasswordPolicy passwordPolicy;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private CompanyRepository companyRepository;

    private UserService service;
    private User user;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("tenant-admin-reset");
        CompanyContext.setCompany(companyId);
        service = new UserService(
            repository,
            mapper,
            auditService,
            passwordEncoder,
            passwordPolicy,
            departmentRepository,
            employeeRepository,
            companyRepository
        );
        user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId("tenant-admin-reset");
        user.setCompanyId(companyId);
        user.setCode("USR-1");
        user.setName("User One");
        user.setUsername("user.one");
        user.setPasswordHash("old-hash");
        user.setTokenVersion(2);
        org.mockito.Mockito.lenient().when(repository.findByIdAndIsDeletedFalse(user.getId())).thenReturn(Optional.of(user));
        org.mockito.Mockito.lenient().when(repository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            "tenant-admin-reset",
            companyId,
            "user.one"
        )).thenReturn(Optional.of(user));
        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse(
            "tenant-admin-reset",
            companyId
        )).thenReturn(Optional.of(new Company()));
        org.mockito.Mockito.lenient().when(repository.save(user)).thenReturn(user);
        org.mockito.Mockito.lenient().when(auditService.snapshot(user)).thenReturn("{}");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void nonBlankChangedAdminPasswordAppliesPolicyAndRevokesExistingTokens() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setPassword("admin-replacement-password");
        when(passwordEncoder.matches("admin-replacement-password", "old-hash"))
            .thenReturn(false);
        when(passwordEncoder.encode("admin-replacement-password")).thenReturn("new-hash");

        service.update(user.getId(), request);

        verify(passwordPolicy).validateNewPassword("admin-replacement-password");
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getTokenVersion()).isEqualTo(3);
        verify(auditService).logInCurrentTransaction(
            "User",
            user.getId(),
            "ADMIN_PASSWORD_RESET",
            user.getCode()
        );
    }

    @Test
    void blankAdminPasswordPreservesExistingCompatibilitySemantics() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setPassword("   ");

        service.update(user.getId(), request);

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        assertThat(user.getTokenVersion()).isEqualTo(2);
        verify(passwordPolicy, never()).validateNewPassword(org.mockito.ArgumentMatchers.anyString());
        verify(auditService, never()).logInCurrentTransaction(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("ADMIN_PASSWORD_RESET"),
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void omittedPasswordOnAProfileOnlyUpdateDoesNotRevokeTokens() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setFullName("Profile-only edit");

        service.update(user.getId(), request);

        assertThat(user.getTokenVersion()).isEqualTo(2);
        verify(passwordEncoder, never()).encode(org.mockito.ArgumentMatchers.anyString());
        verify(auditService, never()).logInCurrentTransaction(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("ADMIN_PASSWORD_RESET"),
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void identicalAdminPasswordIsANoOpEvenForALegacyShortCredential() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setPassword("legacy-short");
        when(passwordEncoder.matches("legacy-short", "old-hash")).thenReturn(true);

        service.update(user.getId(), request);

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        assertThat(user.getTokenVersion()).isEqualTo(2);
        verify(passwordPolicy, never()).validateNewPassword(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void createDerivesDepartmentFromLinkedEmployee() {
        Department department = new Department();
        department.setId(UUID.randomUUID());
        Employee employee = employee(department);
        UserCreateRequest request = createRequest(employee.getId());
        User created = new User();
        created.setCompanyId(companyId);
        created.setUsername("linked.employee");
        created.setName("Linked Employee");
        when(employeeRepository.findByIdAndIsDeletedFalse(employee.getId())).thenReturn(Optional.of(employee));
        when(repository.findByTenantIdAndEmployee_IdAndIsDeletedFalse(
            "tenant-admin-reset", employee.getId())).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            "tenant-admin-reset", companyId, "linked.employee")).thenReturn(Optional.empty());

        service.beforeCreate(created, request);

        assertThat(created.getDepartment()).isSameAs(department);
        assertThat(created.getEmployee()).isSameAs(employee);
    }

    @Test
    void createRejectsDepartmentDifferentFromLinkedEmployee() {
        Department userDepartment = new Department();
        userDepartment.setId(UUID.randomUUID());
        Department employeeDepartment = new Department();
        employeeDepartment.setId(UUID.randomUUID());
        Employee employee = employee(employeeDepartment);
        UserCreateRequest request = createRequest(employee.getId());
        request.setDepartmentId(userDepartment.getId());
        User created = new User();
        created.setCompanyId(companyId);
        created.setUsername("linked.employee");
        created.setName("Linked Employee");
        when(departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
            userDepartment.getId(), "tenant-admin-reset", companyId)).thenReturn(Optional.of(userDepartment));
        when(employeeRepository.findByIdAndIsDeletedFalse(employee.getId())).thenReturn(Optional.of(employee));
        when(repository.findByTenantIdAndEmployee_IdAndIsDeletedFalse(
            "tenant-admin-reset", employee.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.beforeCreate(created, request))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("must match");
    }

    private Employee employee(Department department) {
        Employee employee = new Employee();
        employee.setId(UUID.randomUUID());
        employee.setTenantId("tenant-admin-reset");
        employee.setCompanyId(companyId);
        employee.setDepartment(department);
        return employee;
    }

    private UserCreateRequest createRequest(UUID employeeId) {
        UserCreateRequest request = new UserCreateRequest();
        request.setCompanyId(companyId);
        request.setEmployeeId(employeeId);
        request.setUsername("linked.employee");
        request.setName("Linked Employee");
        return request;
    }
}

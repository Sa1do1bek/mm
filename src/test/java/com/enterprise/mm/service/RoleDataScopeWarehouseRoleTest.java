package com.enterprise.mm.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.DemandPlanningCycleResponse;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DepartmentWarehouseAccessRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class RoleDataScopeWarehouseRoleTest {
    @Mock EmployeeRepository employeeRepository;
    @Mock DepartmentRepository departmentRepository;
    @Mock UserRepository userRepository;
    @Mock DepartmentWarehouseAccessRepository departmentWarehouseAccessRepository;

    private RoleDataScopeService service;

    @BeforeEach
    void setUp() {
        service = new RoleDataScopeService(employeeRepository, departmentRepository, userRepository,
            departmentWarehouseAccessRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAREHOUSE_HEAD", "WAREHOUSE_KEEPER", "DEPARTMENT_HEAD"})
    void warehouseRoleRemainsWarehouseScopedWithoutResolvedEmployee(String role) {
        String username = "warehouse-user";
        authenticate(username, role);
        when(userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT,
            username
        )).thenReturn(Optional.empty());

        assertTrue(service.currentScope().warehouseScoped());
    }

    @Test
    void unrelatedUnrestrictedRoleDoesNotEraseWarehouseScope() {
        String username = "warehouse-supplier";
        authenticate(username, "WAREHOUSE_KEEPER", "SUPPLIER_MANAGER");
        when(userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT,
            username
        )).thenReturn(Optional.empty());

        RoleDataScopeService.DataScope scope = service.currentScope();

        assertFalse(scope.hasDocumentScope());
        assertTrue(scope.warehouseScoped());
        assertFalse(service.canAccessWarehouse(UUID.randomUUID()));
    }

    @Test
    void adminWarehouseRoleRemainsGloballyUnrestricted() {
        authenticate("warehouse-admin", "ADMIN", "WAREHOUSE_KEEPER");

        RoleDataScopeService.DataScope scope = service.currentScope();

        assertFalse(scope.warehouseScoped());
        assertTrue(service.canAccessWarehouse(UUID.randomUUID()));
    }

    @Test
    void assignedDepartmentIsAvailableForGloballyScopedRoles() {
        UUID departmentId = UUID.randomUUID();
        authenticate("manager", "MM_MANAGER");
        Department department = new Department();
        department.setId(departmentId);
        User user = new User();
        user.setDepartment(department);
        when(userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT, CompanyContext.DEFAULT_COMPANY_ID, "manager"))
            .thenReturn(Optional.of(user));

        assertNull(service.currentScope().departmentId());
        assertEquals(departmentId, service.assignedDepartmentId());
    }

    @Test
    void assignedDepartmentMatchesTheDepartmentShownInTheUserProfile() {
        UUID profileDepartmentId = UUID.randomUUID();
        Department profileDepartment = new Department();
        profileDepartment.setId(profileDepartmentId);
        Department employeeDepartment = new Department();
        employeeDepartment.setId(UUID.randomUUID());
        Employee employee = new Employee();
        employee.setDepartment(employeeDepartment);
        User user = new User();
        user.setDepartment(profileDepartment);
        user.setEmployee(employee);
        authenticate("department-head", "DEPARTMENT_HEAD");
        when(userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT, CompanyContext.DEFAULT_COMPANY_ID, "department-head"))
            .thenReturn(Optional.of(user));

        assertEquals(profileDepartmentId, service.assignedDepartmentId());
    }

    @Test
    void warehouseRoleReceivesAllWarehousesMappedToItsDepartment() {
        UUID departmentId = UUID.randomUUID();
        UUID firstWarehouse = UUID.randomUUID();
        UUID secondWarehouse = UUID.randomUUID();
        authenticate("multi-warehouse", "WAREHOUSE_OPERATOR");
        mockCurrentEmployee("multi-warehouse", departmentId);
        when(departmentWarehouseAccessRepository.findActiveWarehouseIds(
            TenantContext.DEFAULT_TENANT, CompanyContext.DEFAULT_COMPANY_ID, departmentId
        )).thenReturn(Set.of(firstWarehouse, secondWarehouse));

        RoleDataScopeService.DataScope scope = service.currentScope();

        assertEquals(Set.of(firstWarehouse, secondWarehouse), scope.warehouseIds());
        assertEquals(Set.of(firstWarehouse, secondWarehouse), service.warehouseReadScope(null, null));
        assertEquals(Set.of(secondWarehouse), service.warehouseReadScope(null, List.of(secondWarehouse)));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> service.warehouseReadScope(UUID.randomUUID(), null));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> service.warehouseReadScope(null, List.of(firstWarehouse, UUID.randomUUID())));
        assertNull(scope.warehouseId());
        assertTrue(service.canAccessWarehouse(firstWarehouse));
        assertTrue(service.canAccessWarehouse(secondWarehouse));
        assertFalse(service.canAccessWarehouse(UUID.randomUUID()));
        SearchCriteria scopedCriteria = service.applyWarehouseScope(SearchCriteria.empty());
        assertEquals(Set.of(firstWarehouse, secondWarehouse), Set.copyOf(scopedCriteria.warehouseIds()));
        assertEquals(new UUID(0L, 0L), scopedCriteria.warehouseId());
    }

    @Test
    void departmentHeadUsesAllDepartmentWarehousesInsteadOfThePersonalAssignment() {
        UUID departmentId = UUID.randomUUID();
        UUID personalWarehouse = UUID.randomUUID();
        UUID firstWarehouse = UUID.randomUUID();
        UUID secondWarehouse = UUID.randomUUID();
        authenticate("department-head", "DEPARTMENT_HEAD");
        mockCurrentEmployeeWithWarehouse("department-head", departmentId, personalWarehouse);
        when(departmentWarehouseAccessRepository.findActiveWarehouseIds(
            TenantContext.DEFAULT_TENANT, CompanyContext.DEFAULT_COMPANY_ID, departmentId
        )).thenReturn(Set.of(firstWarehouse, secondWarehouse));

        var scope = service.currentScope();
        assertTrue(scope.warehouseScoped());
        assertEquals(Set.of(firstWarehouse, secondWarehouse), scope.warehouseIds());
        assertNull(scope.warehouseId());
        assertTrue(service.canAccessWarehouse(firstWarehouse));
        assertTrue(service.canAccessWarehouse(secondWarehouse));
        assertFalse(service.canAccessWarehouse(personalWarehouse));
        assertFalse(service.canAccessWarehouse(UUID.randomUUID()));
        assertTrue(service.canAccessDepartmentAsset(departmentId, null));
        assertTrue(service.canAccessDepartmentAsset(null, secondWarehouse));
        assertFalse(service.canAccessDepartmentAsset(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void departmentHeadWithoutDepartmentGrantsCannotFallBackToPersonalWarehouse() {
        authenticate("department-head", "DEPARTMENT_HEAD");
        mockCurrentEmployeeWithWarehouse("department-head", UUID.randomUUID(), UUID.randomUUID());
        assertTrue(service.currentScope().warehouseScoped());
        assertTrue(service.currentScope().warehouseIds().isEmpty());
        assertFalse(service.canAccessWarehouse(UUID.randomUUID()));
    }

    @Test
    void directEmployeeWarehouseOverridesDepartmentWarehouses() {
        UUID departmentId = UUID.randomUUID();
        UUID assignedWarehouse = UUID.randomUUID();
        authenticate("multi-warehouse", "WAREHOUSE_OPERATOR");
        mockCurrentEmployeeWithWarehouse("multi-warehouse", departmentId, assignedWarehouse);

        RoleDataScopeService.DataScope scope = service.currentScope();
        assertEquals(assignedWarehouse, scope.warehouseId());
        assertEquals(Set.of(assignedWarehouse), scope.warehouseIds());
        assertEquals(assignedWarehouse, service.effectiveWarehouseId(null));
    }

    @Test
    void departmentScopeDoesNotInventASingleWarehouse() {
        UUID departmentId = UUID.randomUUID();
        authenticate("multi-warehouse", "WAREHOUSE_OPERATOR");
        mockCurrentEmployee("multi-warehouse", departmentId);
        when(departmentWarehouseAccessRepository.findActiveWarehouseIds(
            TenantContext.DEFAULT_TENANT, CompanyContext.DEFAULT_COMPANY_ID, departmentId
        )).thenReturn(Set.of(UUID.randomUUID(), UUID.randomUUID()));

        assertNull(service.currentScope().warehouseId());
    }

    @Test
    void personalWarehouseCommandsRejectMultipleAssignmentsInsteadOfChoosingOne() {
        UUID departmentId = UUID.randomUUID();
        authenticate("multi-warehouse", "WAREHOUSE_OPERATOR");
        Department department = new Department(); department.setId(departmentId);
        Employee employee = new Employee(); employee.setDepartment(department);
        employee.setTenantId(TenantContext.DEFAULT_TENANT); employee.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        when(employeeRepository.findByTenantIdAndPersonnelNumberAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT, "multi-warehouse")).thenReturn(Optional.of(employee));
        when(departmentWarehouseAccessRepository.findActiveWarehouseIds(
            TenantContext.DEFAULT_TENANT, CompanyContext.DEFAULT_COMPANY_ID, departmentId
        )).thenReturn(Set.of(UUID.randomUUID(), UUID.randomUUID()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, service::requireAssignedWarehouseId);
    }

    @Test
    void departmentWorkerCanMutateOwnDepartmentDocument() {
        UUID ownDepartmentId = UUID.randomUUID();
        authenticate("department-worker", "DEPARTMENT_WORKER");
        mockCurrentEmployee("department-worker", ownDepartmentId);

        service.assertDepartmentDocumentMutationAllowed(ownDepartmentId, "Demand plan");
    }

    @Test
    void departmentWorkerCannotMutateOtherDepartmentDocument() {
        authenticate("department-worker", "DEPARTMENT_WORKER");
        mockCurrentEmployee("department-worker", UUID.randomUUID());

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.assertDepartmentDocumentMutationAllowed(UUID.randomUUID(), "Demand plan")
        );

        assertTrue(exception.getMessage().contains("Demand plan can be created or edited only in the user's permitted department"));
    }

    @Test
    void departmentHeadCannotMutateManagedDepartmentDocument() {
        UUID managedDepartmentId = UUID.randomUUID();
        authenticate("department-head", "DEPARTMENT_HEAD");
        mockCurrentEmployee("department-head", UUID.randomUUID(), managedDepartmentId);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.assertDepartmentDocumentMutationAllowed(managedDepartmentId, "Demand plan")
        );

        assertTrue(exception.getMessage().contains("Demand plan can be created or edited only in the user's permitted department"));
    }

    @Test
    void departmentHeadCanMutateOwnDepartmentDocument() {
        UUID ownDepartmentId = UUID.randomUUID();
        authenticate("department-head", "DEPARTMENT_HEAD");
        mockCurrentEmployee("department-head", ownDepartmentId, UUID.randomUUID());

        service.assertDepartmentDocumentMutationAllowed(ownDepartmentId, "Demand plan");
    }

    @Test
    void departmentHeadCanReadOnlyOwnDepartmentDocument() {
        UUID ownDepartmentId = UUID.randomUUID();
        UUID managedDepartmentId = UUID.randomUUID();
        authenticate("department-head", "DEPARTMENT_HEAD");
        mockCurrentEmployee("department-head", ownDepartmentId, managedDepartmentId);

        assertTrue(service.canAccessDocument("other-user", null, ownDepartmentId));
        assertFalse(service.canAccessDocument("department-head", null, managedDepartmentId));
    }

    @Test
    void departmentCycleOwnerCannotBypassCollectingAndDepartmentEligibility() {
        authenticate("department-worker", "DEPARTMENT_WORKER");
        mockCurrentEmployee("department-worker", UUID.randomUUID());
        DemandPlanningCycleResponse cycle = new DemandPlanningCycleResponse();
        cycle.setOwnerUsername("department-worker");

        assertFalse(service.canAccessPayload(cycle));
    }

    @Test
    void unassignedDepartmentCycleOwnerCannotBypassParticipantEligibility() {
        authenticate("new-worker", "DEPARTMENT_WORKER");
        when(userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT,
            "new-worker"
        )).thenReturn(Optional.empty());
        DemandPlanningCycleResponse cycle = new DemandPlanningCycleResponse();
        cycle.setOwnerUsername("new-worker");

        assertFalse(service.canAccessPayload(cycle));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST"})
    void procurementRolesCanAccessPlanningCycleCreatedByAnotherDepartmentUser(String role) {
        String username = role.toLowerCase(java.util.Locale.ROOT);
        authenticate(username, role);
        mockCurrentEmployee(username, UUID.randomUUID());
        DemandPlanningCycleResponse cycle = new DemandPlanningCycleResponse();
        cycle.setOwnerUsername("department-worker");

        assertTrue(service.canAccessPayload(cycle));
    }

    @Test
    void departmentWorkerUsesDepartmentAssignedDirectlyOnUserAccount() {
        UUID ownDepartmentId = UUID.randomUUID();
        authenticate("new-worker", "DEPARTMENT_WORKER");
        Department department = new Department();
        department.setId(ownDepartmentId);
        User user = new User();
        user.setUsername("new-worker");
        user.setTenantId(TenantContext.DEFAULT_TENANT);
        user.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        user.setDepartment(department);
        when(userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT,
            "new-worker"
        )).thenReturn(Optional.of(user));

        RoleDataScopeService.DataScope scope = service.currentScope();

        assertTrue(scope.hasDocumentScope());
        assertTrue(ownDepartmentId.equals(scope.departmentId()));
        service.assertDepartmentDocumentMutationAllowed(ownDepartmentId, "Demand plan");
    }

    @Test
    void adminCanMutateAnyDepartmentDocument() {
        authenticate("admin", "ADMIN");

        service.assertDepartmentDocumentMutationAllowed(UUID.randomUUID(), "Demand plan");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST"})
    void procurementRolesCannotBypassDepartmentDocumentMutation(String role) {
        UUID ownDepartmentId = UUID.randomUUID();
        authenticate(role.toLowerCase(java.util.Locale.ROOT), role);
        mockCurrentEmployee(role.toLowerCase(java.util.Locale.ROOT), ownDepartmentId);

        service.assertDepartmentDocumentMutationAllowed(ownDepartmentId, "Demand plan");

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.assertDepartmentDocumentMutationAllowed(UUID.randomUUID(), "Demand plan")
        );

        assertTrue(exception.getMessage().contains("Demand plan can be created or edited only in the user's permitted department"));
    }

    private void authenticate(String username, String... roles) {
        TenantContext.setTenant(TenantContext.DEFAULT_TENANT);
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(roles).stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList()
            )
        );
    }

    private void mockCurrentEmployee(String username, UUID departmentId, UUID... managedDepartmentIds) {
        Department department = new Department();
        department.setId(departmentId);
        Employee employee = new Employee();
        employee.setId(UUID.randomUUID());
        employee.setTenantId(TenantContext.DEFAULT_TENANT);
        employee.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        employee.setPersonnelNumber(username);
        employee.setDepartment(department);
        when(userRepository.findByTenantIdAndUsernameAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT,
            username
        )).thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndPersonnelNumberAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT,
            username
        )).thenReturn(Optional.of(employee));
        when(departmentRepository.findByTenantIdAndCompanyIdAndManagerEmployee_IdAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT,
            CompanyContext.DEFAULT_COMPANY_ID,
            employee.getId()
        )).thenReturn(List.of(managedDepartmentIds).stream()
            .map(id -> {
                Department managed = new Department();
                managed.setId(id);
                return managed;
            })
            .toList());
    }

    private void mockCurrentEmployeeWithWarehouse(String username, UUID departmentId, UUID warehouseId) {
        mockCurrentEmployee(username, departmentId);
        Employee employee = employeeRepository.findByTenantIdAndPersonnelNumberAndIsDeletedFalse(
            TenantContext.DEFAULT_TENANT, username).orElseThrow();
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        employee.setWarehouse(warehouse);
    }
}

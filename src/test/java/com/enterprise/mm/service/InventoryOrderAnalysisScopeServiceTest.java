package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DepartmentWarehouseAccessRepository;
import com.enterprise.mm.service.RoleDataScopeService.DataScope;
import com.enterprise.mm.service.inventory.InventoryAccessService;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class InventoryOrderAnalysisScopeServiceTest {
    private final UUID company = UUID.randomUUID();
    private final UUID managed = UUID.randomUUID();
    private final UUID managedSecond = UUID.randomUUID();
    private final UUID otherCompanyDepartment = UUID.randomUUID();
    private final InventoryAccessService access = mock(InventoryAccessService.class);
    private final RoleDataScopeService roleScope = mock(RoleDataScopeService.class);
    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final DepartmentWarehouseAccessRepository warehouses = mock(DepartmentWarehouseAccessRepository.class);
    private final InventoryOrderAnalysisScopeService service =
        new InventoryOrderAnalysisScopeService(access, roleScope, departments, warehouses);

    @BeforeEach
    void setup() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(company);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void headReceivesOnlyActiveManagedDepartmentsInCurrentCompany() {
        when(access.hasRole("DEPARTMENT_HEAD")).thenReturn(true);
        when(roleScope.currentScope()).thenReturn(new DataScope(true, true, false, Set.of(), Set.of(),
            Set.of(managed, managedSecond, otherCompanyDepartment), null, null));
        when(departments.findByIdAndIsDeletedFalse(managed)).thenReturn(Optional.of(department(managed, company)));
        when(departments.findByIdAndIsDeletedFalse(managedSecond)).thenReturn(Optional.of(department(managedSecond, company)));
        when(departments.findByIdAndIsDeletedFalse(otherCompanyDepartment))
            .thenReturn(Optional.of(department(otherCompanyDepartment, UUID.randomUUID())));
        when(warehouses.findActiveWarehouseIds("tenant-a", company, managed)).thenReturn(Set.of(UUID.randomUUID()));

        var scope = service.current();
        assertThat(scope.mode()).isEqualTo("DEPARTMENTS");
        assertThat(scope.departmentIds()).containsExactlyInAnyOrder(managed, managedSecond);
        assertThat(scope.targets()).hasSize(2).allSatisfy(target -> assertThat(target.name()).isEqualTo("Unit A"));
    }

    @Test
    void headWithoutManagedDepartmentIsDenied() {
        when(access.hasRole("DEPARTMENT_HEAD")).thenReturn(true);
        when(roleScope.currentScope()).thenReturn(DataScope.unrestricted());
        assertThatThrownBy(service::current).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void headWithoutManagerRecordUsesOnlyTheirAssignedActiveDepartment() {
        when(access.hasRole("DEPARTMENT_HEAD")).thenReturn(true);
        when(roleScope.currentScope()).thenReturn(DataScope.unrestricted());
        when(roleScope.assignedDepartmentId()).thenReturn(managed);
        Department assigned = department(managed, company);
        assigned.setManagerEmployee(null);
        when(departments.findByIdAndIsDeletedFalse(managed)).thenReturn(Optional.of(assigned));

        var scope = service.current();
        assertThat(scope.mode()).isEqualTo("DEPARTMENTS");
        assertThat(scope.departmentIds()).containsExactly(managed);
    }

    @Test
    void headAssignedToOtherCompanyStillCannotAnalyze() {
        when(access.hasRole("DEPARTMENT_HEAD")).thenReturn(true);
        when(roleScope.currentScope()).thenReturn(DataScope.unrestricted());
        when(roleScope.assignedDepartmentId()).thenReturn(managed);
        when(departments.findByIdAndIsDeletedFalse(managed))
            .thenReturn(Optional.of(department(managed, UUID.randomUUID())));

        assertThatThrownBy(service::current).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void regularUserReceivesOnlyTheirAssignedDepartment() {
        when(roleScope.assignedDepartmentId()).thenReturn(managed);
        Department assigned = department(managed, company);
        assigned.setManagerEmployee(null);
        when(departments.findByIdAndIsDeletedFalse(managed)).thenReturn(Optional.of(assigned));

        var scope = service.current();
        assertThat(scope.mode()).isEqualTo("DEPARTMENTS");
        assertThat(scope.departmentIds()).containsExactly(managed);
        assertThat(scope.targets()).hasSize(1);
    }

    @Test
    void regularUserWithoutAssignedDepartmentIsDenied() {
        assertThatThrownBy(service::current).isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("assigned department");
    }

    @Test
    void adminReceivesCompanyWideScope() {
        when(access.hasRole("ADMIN")).thenReturn(true);
        assertThat(service.current()).isEqualTo(new InventoryOrderAnalysisScopeService.Scope(
            "ALL", company, List.of(), List.of()));
    }

    private Department department(UUID id, UUID companyId) {
        Department department = new Department();
        department.setId(id);
        department.setTenantId("tenant-a");
        department.setCompanyId(companyId);
        department.setName("Unit A");
        department.setStatus("ACTIVE");
        Employee manager = new Employee();
        manager.setStatus("ACTIVE");
        department.setManagerEmployee(manager);
        return department;
    }
}

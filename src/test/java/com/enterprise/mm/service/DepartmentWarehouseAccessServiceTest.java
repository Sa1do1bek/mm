package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.DepartmentType;
import com.enterprise.mm.entity.DepartmentWarehouseAccess;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.DepartmentMapper;
import com.enterprise.mm.payload.DepartmentCreateRequest;
import com.enterprise.mm.payload.DepartmentResponse;
import com.enterprise.mm.payload.DepartmentUpdateRequest;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DepartmentTypeRepository;
import com.enterprise.mm.repository.DepartmentWarehouseAccessRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DepartmentWarehouseAccessServiceTest {
    private static final String TENANT = "department-warehouse-test";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final DepartmentMapper mapper = mock(DepartmentMapper.class);
    private final AuditService audit = mock(AuditService.class);
    private final DepartmentTypeRepository types = mock(DepartmentTypeRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final DepartmentWarehouseAccessRepository access = mock(DepartmentWarehouseAccessRepository.class);
    private final DepartmentService service = new DepartmentService(
        departments, mapper, audit, types, employees, warehouses, access);

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        DepartmentType type = new DepartmentType();
        when(types.findFirstByTenantIdAndCompanyIdAndCodeIgnoreCaseAndIsDeletedFalseOrderByCreatedAtAsc(
                TENANT, COMPANY, "DEPARTMENT"))
            .thenReturn(java.util.Optional.of(type));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createSynchronizesValidatedWarehouseGrants() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        DepartmentCreateRequest request = new DepartmentCreateRequest();
        request.setName("Maintenance");
        request.setWarehouseIds(Set.of(firstId, secondId));
        Department department = department();
        Warehouse first = warehouse(firstId);
        Warehouse second = warehouse(secondId);
        DepartmentResponse response = new DepartmentResponse();

        when(mapper.toEntity(request)).thenReturn(department);
        when(departments.save(department)).thenReturn(department);
        when(warehouses.findByTenantIdAndCompanyIdAndIdIn(TENANT, COMPANY, Set.of(firstId, secondId)))
            .thenReturn(List.of(first, second));
        when(access.findByTenantIdAndCompanyIdAndDepartment_Id(TENANT, COMPANY, department.getId()))
            .thenReturn(List.of());
        when(access.findActiveWarehouseIds(TENANT, COMPANY, department.getId()))
            .thenReturn(Set.of(firstId, secondId));
        when(mapper.toResponse(department)).thenReturn(response);

        DepartmentResponse result = service.create(request);

        ArgumentCaptor<List<DepartmentWarehouseAccess>> grants = ArgumentCaptor.forClass(List.class);
        verify(access).saveAllAndFlush(grants.capture());
        assertThat(grants.getValue()).extracting(item -> item.getWarehouse().getId())
            .containsExactlyInAnyOrder(firstId, secondId);
        assertThat(result.getWarehouseIds()).containsExactlyInAnyOrder(firstId, secondId);
    }

    @Test
    void updateReplacesAndFlushesWarehouseGrantsInsideDepartmentTransaction() {
        UUID departmentId = UUID.randomUUID();
        UUID oldWarehouseId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Department department = department();
        department.setId(departmentId);
        Warehouse oldWarehouse = warehouse(oldWarehouseId);
        Warehouse first = warehouse(firstId);
        Warehouse second = warehouse(secondId);
        DepartmentWarehouseAccess oldGrant = new DepartmentWarehouseAccess();
        oldGrant.setId(UUID.randomUUID());
        oldGrant.setDepartment(department);
        oldGrant.setWarehouse(oldWarehouse);
        DepartmentUpdateRequest request = new DepartmentUpdateRequest();
        request.setName("Maintenance");
        request.setWarehouseIds(Set.of(firstId, secondId));
        DepartmentResponse response = new DepartmentResponse();

        when(departments.findByIdAndIsDeletedFalse(departmentId)).thenReturn(java.util.Optional.of(department));
        when(audit.snapshot(department)).thenReturn("{}");
        when(departments.save(department)).thenReturn(department);
        when(warehouses.findByTenantIdAndCompanyIdAndIdIn(TENANT, COMPANY, Set.of(firstId, secondId)))
            .thenReturn(List.of(first, second));
        when(access.findByTenantIdAndCompanyIdAndDepartment_Id(TENANT, COMPANY, departmentId))
            .thenReturn(List.of(oldGrant));
        when(access.findActiveWarehouseIds(TENANT, COMPANY, departmentId)).thenReturn(Set.of(firstId, secondId));
        when(mapper.toResponse(department)).thenReturn(response);

        DepartmentResponse result = service.update(departmentId, request);

        verify(access).deleteAllInBatch(List.of(oldGrant));
        verify(access).flush();
        ArgumentCaptor<List<DepartmentWarehouseAccess>> grants = ArgumentCaptor.forClass(List.class);
        verify(access).saveAllAndFlush(grants.capture());
        assertThat(grants.getValue()).extracting(item -> item.getWarehouse().getId())
            .containsExactlyInAnyOrder(firstId, secondId);
        verify(audit).logInCurrentTransaction("Department", departmentId, "UPDATE", department.getCode(), "{}", department);
        assertThat(result.getWarehouseIds()).containsExactlyInAnyOrder(firstId, secondId);
    }

    @Test
    void createRejectsWarehouseOutsideCurrentCompanyOrInactiveWarehouse() {
        UUID warehouseId = UUID.randomUUID();
        DepartmentCreateRequest request = new DepartmentCreateRequest();
        request.setName("Maintenance");
        request.setWarehouseIds(Set.of(warehouseId));
        Department department = department();
        when(mapper.toEntity(request)).thenReturn(department);
        when(departments.save(department)).thenReturn(department);
        when(warehouses.findByTenantIdAndCompanyIdAndIdIn(TENANT, COMPANY, Set.of(warehouseId)))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("warehouseIds");
    }

    private Department department() {
        Department value = new Department();
        value.setId(UUID.randomUUID());
        value.setTenantId(TENANT);
        value.setCompanyId(COMPANY);
        value.setName("Maintenance");
        value.setCode("MAINT");
        return value;
    }

    private Warehouse warehouse(UUID id) {
        Warehouse value = new Warehouse();
        value.setId(id);
        value.setTenantId(TENANT);
        value.setCompanyId(COMPANY);
        value.setStatus("ACTIVE");
        return value;
    }
}

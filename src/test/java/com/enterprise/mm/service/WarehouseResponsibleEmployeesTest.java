package com.enterprise.mm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.mapper.WarehouseMapper;
import com.enterprise.mm.payload.WarehouseResponse;
import com.enterprise.mm.payload.WarehouseSearchRequest;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WarehouseResponsibleEmployeesTest {
    private static final String TENANT_ID = "warehouse-responsible-employees-test";
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_EMPLOYEE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_EMPLOYEE_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Mock WarehouseRepository repository;
    @Mock EmployeeRepository employeeRepository;
    @Mock WarehouseMapper mapper;
    @Mock AuditService auditService;
    @Mock RoleDataScopeService roleDataScopeService;

    private WarehouseService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
        service = new WarehouseService(repository, employeeRepository, mapper, auditService, roleDataScopeService);
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void searchIncludesResponsibleEmployeeIdsInAssignmentOrder() {
        Warehouse warehouse = new Warehouse();
        warehouse.setId(WAREHOUSE_ID);
        WarehouseResponse response = new WarehouseResponse();
        Employee firstEmployee = employee(FIRST_EMPLOYEE_ID);
        Employee secondEmployee = employee(SECOND_EMPLOYEE_ID);
        when(roleDataScopeService.currentScope()).thenReturn(RoleDataScopeService.DataScope.unrestricted());

        when(repository.search(
            eq(TENANT_ID),
            eq(COMPANY_ID),
            eq(false),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(true),
            eq(false),
            anyCollection(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(Pageable.unpaged())
        )).thenReturn(new PageImpl<>(List.of(warehouse)));
        when(mapper.toResponse(warehouse)).thenReturn(response);
        when(employeeRepository.findByTenantIdAndCompanyIdAndWarehouse_IdAndIsDeletedFalse(
            TENANT_ID,
            COMPANY_ID,
            WAREHOUSE_ID
        )).thenReturn(List.of(firstEmployee, secondEmployee));

        WarehouseResponse result = service.search(WarehouseSearchRequest.empty(), Pageable.unpaged())
            .getContent()
            .getFirst();

        assertEquals(List.of(FIRST_EMPLOYEE_ID, SECOND_EMPLOYEE_ID), result.getResponsibleEmployeeIds());
        verify(employeeRepository).findByTenantIdAndCompanyIdAndWarehouse_IdAndIsDeletedFalse(
            TENANT_ID,
            COMPANY_ID,
            WAREHOUSE_ID
        );
    }

    private Employee employee(UUID id) {
        Employee employee = new Employee();
        employee.setId(id);
        return employee;
    }
}

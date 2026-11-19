package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.WarehouseMapper;
import com.enterprise.mm.payload.WarehouseSearchRequest;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WarehouseMasterScopeTest {
    private final UUID assigned = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    @Mock WarehouseRepository repository;
    @Mock EmployeeRepository employees;
    @Mock WarehouseMapper mapper;
    @Mock AuditService audit;
    @Mock RoleDataScopeService scope;
    WarehouseService service;

    @BeforeEach void setUp() {
        TenantContext.setTenant("warehouse-scope-test");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        service = new WarehouseService(repository, employees, mapper, audit, scope);
    }

    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void appliesWarehouseScopeInTheDatabaseQueryBeforePagination() {
        when(scope.currentScope()).thenReturn(new RoleDataScopeService.DataScope(
            false, false, true, Set.of(), Set.of(), Set.of(), null, assigned, Set.of(assigned)));
        when(repository.search(eq("warehouse-scope-test"), eq(CompanyContext.DEFAULT_COMPANY_ID), eq(false),
            isNull(), isNull(), isNull(), isNull(), eq(true), eq(true), eq(Set.of(assigned)), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(Page.empty());
        assertThat(service.search(WarehouseSearchRequest.empty(), Pageable.ofSize(20)).getTotalElements()).isZero();
        verify(scope).currentScope();
    }

    @Test void rejectsAnotherWarehouseBeforeReturningOrEditingItsMaster() {
        Warehouse warehouse = warehouse(other);
        when(repository.findByIdAndIsDeletedFalse(other)).thenReturn(Optional.of(warehouse));
        assertThatThrownBy(() -> service.get(other)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(mapper, employees, audit);
        verify(repository, never()).save(any());
    }

    @Test void restoreAlsoChecksTheAssignedWarehouseBeforeAnyMutation() {
        when(repository.findAnyById(other)).thenReturn(Optional.of(warehouse(other)));
        assertThatThrownBy(() -> service.restore(other)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(mapper, employees, audit);
    }

    private Warehouse warehouse(UUID id) {
        Warehouse value = new Warehouse();
        value.setId(id); value.setTenantId("warehouse-scope-test");
        value.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        return value;
    }
}

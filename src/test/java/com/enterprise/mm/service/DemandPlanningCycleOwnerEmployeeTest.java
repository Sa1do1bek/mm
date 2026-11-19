package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.DemandPlanningCycleMapper;
import com.enterprise.mm.payload.DemandPlanningCycleCreateRequest;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class DemandPlanningCycleOwnerEmployeeTest {
    private static final String TENANT = "tenant-demand-cycle-owner";
    private static final UUID COMPANY_ID =
        UUID.fromString("00000000-0000-0000-0000-000000007704");

    private DemandPlanningCycleRepository cycleRepository;
    private EmployeeRepository employeeRepository;
    private UserRepository userRepository;
    private DemandPlanningCycleService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        cycleRepository = mock(DemandPlanningCycleRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        userRepository = mock(UserRepository.class);
        when(cycleRepository.save(any(DemandPlanningCycle.class))).thenAnswer(invocation -> {
            DemandPlanningCycle cycle = invocation.getArgument(0);
            cycle.setId(UUID.randomUUID());
            cycle.setTenantId(TENANT);
            return cycle;
        });
        service = new DemandPlanningCycleService(
            cycleRepository,
            new DemandPlanningCycleMapper(),
            mock(DemandPlanRepository.class),
            mock(DemandPlanItemRepository.class),
            mock(IntegrationEventService.class),
            mock(AuditService.class),
            mock(EntityManager.class),
            mock(BudgetItemService.class),
            mock(RoleDataScopeService.class),
            employeeRepository,
            userRepository,
            mock(ApprovalEngineService.class),
            mock(DemandPlanNamingService.class)
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("creator.user", "n/a")
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void createResolvesAndPersistsOwnerEmployeeId() {
        Employee employee = employee("Planner Employee");
        User user = new User();
        user.setUsername("planner.employee");
        user.setEmployee(employee);
        employee.setUser(user);
        when(employeeRepository.findByIdAndIsDeletedFalse(employee.getId()))
            .thenReturn(Optional.of(employee));

        DemandPlanningCycleCreateRequest request = new DemandPlanningCycleCreateRequest();
        request.setName("Annual demand cycle");
        request.setPlanYear(2027);
        request.setPeriodType("YEAR");
        request.setOwnerEmployeeId(employee.getId());

        var response = service.create(request);

        assertThat(response.getOwnerEmployeeId()).isEqualTo(employee.getId());
        assertThat(response.getOwnerEmployeeName()).isEqualTo("Planner Employee");
        assertThat(response.getOwnerUsername()).isEqualTo("planner.employee");
        verify(cycleRepository).save(org.mockito.ArgumentMatchers.argThat(cycle ->
            cycle.getOwnerEmployee() == employee
                && "planner.employee".equals(cycle.getOwnerUsername())
        ));
    }

    @Test
    void createRejectsOwnerEmployeeFromAnotherCompany() {
        Employee employee = employee("Other company employee");
        employee.setCompanyId(UUID.randomUUID());
        when(employeeRepository.findByIdAndIsDeletedFalse(employee.getId()))
            .thenReturn(Optional.of(employee));
        DemandPlanningCycleCreateRequest request = new DemandPlanningCycleCreateRequest();
        request.setName("Annual demand cycle");
        request.setOwnerEmployeeId(employee.getId());

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Employee not found");

        verify(cycleRepository, never()).save(any());
    }

    @Test
    void createDefaultsOwnerToAuthenticatedCreatorWhenOwnerNotProvided() {
        Employee employee = employee("Creator Employee");
        User user = new User();
        user.setUsername("creator.user");
        user.setEmployee(employee);
        employee.setUser(user);
        when(userRepository.resolveActiveUserByUsername(TENANT, COMPANY_ID, "creator.user"))
            .thenReturn(Optional.of(user));

        DemandPlanningCycleCreateRequest request = new DemandPlanningCycleCreateRequest();
        request.setName("Annual demand cycle");
        request.setPlanYear(2027);
        request.setPeriodType("YEAR");

        var response = service.create(request);

        assertThat(response.getOwnerUsername()).isEqualTo("creator.user");
        assertThat(response.getOwnerEmployeeId()).isEqualTo(employee.getId());
        assertThat(response.getOwnerEmployeeName()).isEqualTo("Creator Employee");
    }

    private Employee employee(String name) {
        Employee employee = new Employee();
        employee.setId(UUID.randomUUID());
        employee.setTenantId(TENANT);
        employee.setCompanyId(COMPANY_ID);
        employee.setName(name);
        employee.setStatus("ACTIVE");
        return employee;
    }
}

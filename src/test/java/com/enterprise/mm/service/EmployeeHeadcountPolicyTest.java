package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Position;
import com.enterprise.mm.enums.EmployeeStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.EmployeeMapper;
import com.enterprise.mm.payload.EmployeeCreateRequest;
import com.enterprise.mm.payload.EmployeeResponse;
import com.enterprise.mm.payload.EmployeeSearchRequest;
import com.enterprise.mm.payload.EmployeeUpdateRequest;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.PositionRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmployeeHeadcountPolicyTest {
    private static final String TENANT_ID = "employee-headcount-policy";
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-0000-0000-000000000021");
    private static final UUID POSITION_ID = UUID.fromString("20000000-0000-0000-0000-000000000021");
    private static final UUID EMPLOYEE_ID = UUID.fromString("30000000-0000-0000-0000-000000000021");

    @Mock private EmployeeRepository repository;
    @Mock private PositionRepository positionRepository;
    @Mock private UserRepository userRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private EmployeeMapper mapper;
    @Mock private AuditService auditService;

    private EmployeeService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
        service = new EmployeeService(repository, positionRepository, userRepository, warehouseRepository, mapper, auditService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createRejectsWhenPositionHeadcountIsAlreadyFull() {
        EmployeeCreateRequest request = new EmployeeCreateRequest();
        request.setName("Capacity Blocked");
        request.setPositionId(POSITION_ID);

        Employee employee = employee(null, position(), EmployeeStatus.ACTIVE);
        when(mapper.toEntity(request)).thenReturn(employee);
        when(positionRepository.findByIdAndIsDeletedFalse(POSITION_ID)).thenReturn(Optional.of(position()));
        when(repository.countOccupiedByPosition(TENANT_ID, COMPANY_ID, POSITION_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Position headcount exceeded");
    }

    @Test
    void updateAllowsExistingOccupantToKeepSamePositionAtCapacity() {
        EmployeeUpdateRequest request = new EmployeeUpdateRequest();
        request.setName("Updated Name");
        request.setPositionId(POSITION_ID);

        Position position = position();
        Employee employee = employee(EMPLOYEE_ID, position, EmployeeStatus.ACTIVE);
        EmployeeResponse response = new EmployeeResponse();

        when(repository.findByIdAndIsDeletedFalse(EMPLOYEE_ID)).thenReturn(Optional.of(employee));
        when(positionRepository.findByIdAndIsDeletedFalse(POSITION_ID)).thenReturn(Optional.of(position));
        when(repository.countOccupiedByPositionExcludingEmployee(TENANT_ID, COMPANY_ID, POSITION_ID, EMPLOYEE_ID)).thenReturn(0L);
        when(repository.save(employee)).thenReturn(employee);
        when(repository.countOccupiedByPosition(TENANT_ID, COMPANY_ID, POSITION_ID)).thenReturn(1L);
        when(positionRepository.save(any(Position.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(employee)).thenReturn(response);

        EmployeeResponse result = service.update(EMPLOYEE_ID, request);

        assertSame(response, result);
    }

    @Test
    void searchRejectsLocalizedEmploymentStatusLabelsBeforeQuerying() {
        EmployeeSearchRequest request = new EmployeeSearchRequest(
            null,
            null,
            null,
            null,
            false,
            "Ma'muriy"
        );

        assertThatThrownBy(() -> service.search(request, org.springframework.data.domain.Pageable.unpaged()))
            .isInstanceOf(BadRequestException.class)
            .hasMessageNotContaining("Ma'muriy")
            .hasMessageContaining("Invalid employee employment status filter")
            .hasMessageContaining("ACTIVE")
            .hasMessageContaining("DISMISSED");
        verifyNoInteractions(repository);
    }

    private Position position() {
        Position position = new Position();
        position.setId(POSITION_ID);
        position.setTenantId(TENANT_ID);
        position.setCompanyId(COMPANY_ID);
        position.setName("Lead Buyer");
        position.setHeadcount(BigDecimal.ONE);
        position.setOccupiedHeadcount(BigDecimal.ONE);
        return position;
    }

    private Employee employee(UUID id, Position position, EmployeeStatus status) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setTenantId(TENANT_ID);
        employee.setCompanyId(COMPANY_ID);
        employee.setName("Employee");
        employee.setStatus("ACTIVE");
        employee.setPosition(position);
        employee.setEmploymentStatus(status);
        return employee;
    }
}

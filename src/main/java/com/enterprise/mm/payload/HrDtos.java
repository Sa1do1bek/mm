package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class HrDtos {
    private HrDtos() {
    }

    public record DepartmentTreeNodeDto(
        UUID id,
        String name,
        String status,
        String departmentType,
        String costCenter,
        UUID managerEmployeeId,
        String managerName,
        long positionCount,
        long employeeCount,
        List<DepartmentTreeNodeDto> children
    ) {
    }

    public record HrSyncRequest(
        String sourceSystem,
        @Valid List<HrDepartmentDto> departments,
        @Valid List<HrPositionDto> positions,
        @Valid List<HrEmployeeDto> employees
    ) {
    }

    public record HrDepartmentDto(
        String externalId,
        String parentExternalId,
        String name,
        String departmentType,
        String costCenter,
        Integer sortOrder,
        String status
    ) {
    }

    public record HrPositionDto(
        String externalId,
        String departmentExternalId,
        String name,
        String positionCategory,
        String grade,
        BigDecimal headcount,
        BigDecimal occupiedHeadcount,
        String status
    ) {
    }

    public record HrEmployeeDto(
        String externalId,
        String personnelNumber,
        String departmentExternalId,
        String positionExternalId,
        String managerExternalId,
        String fullName,
        String email,
        String phone,
        String employmentStatus,
        LocalDate hireDate,
        LocalDate dismissalDate
    ) {
    }

    public record HrSyncResultDto(
        int departmentsUpserted,
        int positionsUpserted,
        int employeesUpserted,
        UUID integrationEventId
    ) {
    }
}
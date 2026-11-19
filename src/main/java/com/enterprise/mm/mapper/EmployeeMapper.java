package com.enterprise.mm.mapper;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Position;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.payload.EmployeeResponse;
import com.enterprise.mm.payload.EmployeeUpdateRequest;
import com.enterprise.mm.payload.EmployeeCreateRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class EmployeeMapper {
    @PersistenceContext
    private EntityManager entityManager;

    public EmployeeResponse toResponse(Employee entity) {
        if (entity == null) {
            return null;
        }
        EmployeeResponse dto = new EmployeeResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setUserId(idOf(entity.getUser()));
        dto.setExternalId(entity.getExternalId());
        dto.setPersonnelNumber(entity.getPersonnelNumber());
        dto.setDepartmentId(idOf(entity.getDepartment()));
        dto.setWarehouseId(idOf(entity.getWarehouse()));
        dto.setWarehouseName(entity.getWarehouse() == null ? null : entity.getWarehouse().getName());
        dto.setPositionId(idOf(entity.getPosition()));
        dto.setManagerEmployeeId(idOf(entity.getManagerEmployee()));
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setEmploymentStatus(entity.getEmploymentStatus());
        dto.setHireDate(entity.getHireDate());
        dto.setDismissalDate(entity.getDismissalDate());
        dto.setRoles(safeCollection(entity.getRoles()).stream().map(RoleName::name).collect(Collectors.toCollection(LinkedHashSet::new)));
        return dto;
    }

    public Employee toEntity(EmployeeCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Employee entity = new Employee();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setExternalId(dto.getExternalId());
        entity.setPersonnelNumber(dto.getPersonnelNumber());
        entity.setDepartment(reference(Department.class, dto.getDepartmentId()));
        entity.setPosition(reference(Position.class, dto.getPositionId()));
        entity.setManagerEmployee(reference(Employee.class, dto.getManagerEmployeeId()));
        entity.setEmail(dto.getEmail());
        entity.setPhone(dto.getPhone());
        entity.setEmploymentStatus(dto.getEmploymentStatus());
        entity.setHireDate(dto.getHireDate());
        entity.setDismissalDate(dto.getDismissalDate());
        return entity;
    }

    public void updateEntity(Employee entity, EmployeeUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setExternalId(dto.getExternalId());
        entity.setPersonnelNumber(dto.getPersonnelNumber());
        entity.setDepartment(reference(Department.class, dto.getDepartmentId()));
        entity.setPosition(reference(Position.class, dto.getPositionId()));
        entity.setManagerEmployee(reference(Employee.class, dto.getManagerEmployeeId()));
        entity.setEmail(dto.getEmail());
        entity.setPhone(dto.getPhone());
        entity.setEmploymentStatus(dto.getEmploymentStatus());
        entity.setHireDate(dto.getHireDate());
        entity.setDismissalDate(dto.getDismissalDate());
    }

    private <T> java.util.Collection<T> safeCollection(java.util.Collection<T> values) {
        return values == null ? java.util.List.of() : values;
    }

    private <T> T reference(Class<T> entityClass, UUID id) {
        return id == null ? null : entityManager.getReference(entityClass, id);
    }

    private UUID idOf(BusinessEntity entity) {
        return entity == null ? null : entity.getId();
    }
}

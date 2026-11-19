package com.enterprise.mm.mapper;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.payload.DepartmentResponse;
import com.enterprise.mm.payload.DepartmentUpdateRequest;
import com.enterprise.mm.payload.DepartmentCreateRequest;
import com.enterprise.mm.entity.Department;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DepartmentMapper {
    @PersistenceContext
    private EntityManager entityManager;

    public DepartmentResponse toResponse(Department entity) {
        if (entity == null) {
            return null;
        }
        DepartmentResponse dto = new DepartmentResponse();
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
        dto.setExternalId(entity.getExternalId());
        dto.setParentDepartmentId(idOf(entity.getParentDepartment()));
        dto.setDepartmentType(entity.getDepartmentType());
        dto.setManagerEmployeeId(idOf(entity.getManagerEmployee()));
        dto.setCostCenter(entity.getCostCenter());
        dto.setHierarchyPath(entity.getHierarchyPath());
        dto.setSortOrder(entity.getSortOrder());
        return dto;
    }

    public Department toEntity(DepartmentCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        Department entity = new Department();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setExternalId(dto.getExternalId());
        entity.setParentDepartment(reference(Department.class, dto.getParentDepartmentId()));
        entity.setDepartmentType(dto.getDepartmentType());
        entity.setManagerEmployee(reference(Employee.class, dto.getManagerEmployeeId()));
        entity.setCostCenter(dto.getCostCenter());
        entity.setHierarchyPath(dto.getHierarchyPath());
        entity.setSortOrder(dto.getSortOrder());
        return entity;
    }

    public void updateEntity(Department entity, DepartmentUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setExternalId(dto.getExternalId());
        entity.setParentDepartment(reference(Department.class, dto.getParentDepartmentId()));
        entity.setDepartmentType(dto.getDepartmentType());
        entity.setManagerEmployee(reference(Employee.class, dto.getManagerEmployeeId()));
        entity.setCostCenter(dto.getCostCenter());
        entity.setHierarchyPath(dto.getHierarchyPath());
        entity.setSortOrder(dto.getSortOrder());
    }

    private <T> T reference(Class<T> entityClass, UUID id) {
        return id == null ? null : entityManager.getReference(entityClass, id);
    }

    private UUID idOf(BusinessEntity entity) {
        return entity == null ? null : entity.getId();
    }
}
